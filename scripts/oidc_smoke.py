"""Real local Keycloak authorization-code/PKCE checks; never print or persist credentials/tokens."""

import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from decimal import Decimal


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Browsers treat localhost HTTP as trustworthy; CookieJar otherwise drops Keycloak's Secure cookies.
        url = urllib.parse.urlsplit(request.full_url)
        if url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1"):
            return True
        return super().return_ok_secure(cookie, request)


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.fields = {}

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == "form" and attributes.get("id") == "kc-form-login":
            self.action = attributes.get("action")
        if tag == "input" and attributes.get("type") == "hidden" and attributes.get("name"):
            self.fields[attributes["name"]] = attributes.get("value", "")


def request_json(url, body=None, token=None, method=None, idempotency_key=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.load(error)
        except (ValueError, UnicodeError):
            return error.code, None


def post_form(url, fields):
    request = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode(),
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.load(response) if response.status != 204 else None
    except urllib.error.HTTPError as error:
        return error.code, None


def code_session(provider, frontend, username, password):
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    fields = {"client_id": "procureflow-web", "response_type": "code", "scope": "openid", "redirect_uri": frontend + "/",
              "state": state, "nonce": secrets.token_urlsafe(32), "code_challenge": challenge, "code_challenge_method": "S256"}
    jar = http.cookiejar.CookieJar(policy=LoopbackCookiePolicy())
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect())
    with opener.open(provider + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(fields), timeout=20) as page:
        form = LoginForm()
        form.feed(page.read().decode())
    if not form.action or urllib.parse.urlsplit(form.action).netloc != urllib.parse.urlsplit(provider).netloc:
        raise RuntimeError("OIDC did not provide an expected same-provider login form")
    credentials = {**form.fields, "username": username, "password": password}
    try:
        opener.open(urllib.request.Request(form.action, data=urllib.parse.urlencode(credentials).encode()), timeout=20).close()
        raise RuntimeError("OIDC login did not redirect with an authorization code")
    except urllib.error.HTTPError as response:
        if response.code != 302:
            raise RuntimeError("OIDC login failed before code exchange") from None
        location = urllib.parse.urlsplit(response.headers["Location"])
    if location.netloc != urllib.parse.urlsplit(frontend).netloc:
        raise RuntimeError("OIDC redirected outside the registered frontend")
    callback = urllib.parse.parse_qs(location.query)
    if callback.get("state") != [state] or "code" not in callback:
        raise RuntimeError("OIDC code/state callback was invalid")
    return {"client_id": "procureflow-web", "grant_type": "authorization_code", "redirect_uri": frontend + "/",
            "code_verifier": verifier, "code": callback["code"][0]}


def check_identity_journey(provider, frontend, password):
    token_endpoint = provider + "/protocol/openid-connect/token"
    bad_pkce = code_session(provider, frontend, "a-employee", password)
    status, _ = post_form(token_endpoint, {**bad_pkce, "code_verifier": secrets.token_urlsafe(48)})
    if status != 400:
        raise RuntimeError("Provider accepted a mismatched PKCE verifier")

    def login(username, check_replay=False):
        exchange = code_session(provider, frontend, username, password)
        status, tokens = post_form(token_endpoint, exchange)
        if status != 200 or not tokens or "access_token" not in tokens:
            raise RuntimeError("Authorization-code exchange failed")
        if check_replay and post_form(token_endpoint, exchange)[0] != 400:
            raise RuntimeError("Provider accepted a replayed authorization code")
        return tokens

    # Keycloak invalidates the replayed code's session; keep this negative case separate from refresh/logout.
    login("a-finance", check_replay=True)
    employee = login("a-employee")
    manager = login("a-manager")
    finance = login("a-finance")
    admin = login("a-admin")
    foreign = login("b-employee")
    first_status, own = request_json(frontend + "/api/me", token=employee["access_token"])
    foreign_status, other = request_json(frontend + "/api/me", token=foreign["access_token"])
    if first_status != 200 or foreign_status != 200 or own["companyId"] == other["companyId"]:
        raise RuntimeError("Real OIDC identities did not resolve to distinct database companies")
    if request_json(frontend + "/api/admin/memberships", token=employee["access_token"])[0] != 403:
        raise RuntimeError("An employee gained administrator authority")
    requests = check_request_journey(frontend, employee["access_token"], manager["access_token"],
                                     foreign["access_token"], admin["access_token"])
    approvals = check_approval_journey(frontend, employee["access_token"], manager["access_token"],
                                        finance["access_token"], foreign["access_token"], admin["access_token"])
    refresh = {"grant_type": "refresh_token", "client_id": "procureflow-web", "refresh_token": employee["refresh_token"]}
    status, renewed = post_form(token_endpoint, refresh)
    if status != 200 or request_json(frontend + "/api/me", token=renewed["access_token"])[0] != 200:
        raise RuntimeError("OIDC refresh did not produce a usable API token")
    update = {"departmentId": own["departmentId"], "active": False, "version": own["version"],
              "roles": ["EMPLOYEE"], "managedDepartmentIds": []}
    status, _ = request_json(frontend + "/api/admin/memberships/" + own["membershipId"], body=update,
                             token=admin["access_token"], method="PUT")
    if status != 200 or request_json(frontend + "/api/me", token=employee["access_token"])[0] != 403:
        raise RuntimeError("An unexpired real JWT bypassed membership revocation")
    status, _ = post_form(provider + "/protocol/openid-connect/logout",
                           {"client_id": "procureflow-web", "refresh_token": renewed["refresh_token"]})
    if status != 204:
        raise RuntimeError("OIDC session logout failed")
    if post_form(token_endpoint, {**refresh, "refresh_token": renewed["refresh_token"]})[0] != 400:
        raise RuntimeError("A logged-out refresh session remained usable")
    print("PASS real Keycloak authorization code + PKCE, code replay denial, refresh, logout and live membership revocation", flush=True)
    return {"flow": "authorization_code", "pkce": "S256", "companies": 2,
            "codeReplayDenied": True, "revocationDenied": True, "refreshAndLogout": True,
            "requests": requests, "approvals": approvals}


def check_request_journey(frontend, employee, manager, foreign, administrator):
    endpoint = frontend + "/api/requests"
    key = str(uuid.uuid4())
    body = {"title": "Local integration request", "justification": "Fictional local-only workflow check", "amount": 50}
    status, created = request_json(endpoint, body, employee, "POST", key)
    if status != 201 or created["status"] != "DRAFT":
        raise RuntimeError("Real OIDC employee could not create a draft")
    status, replay = request_json(endpoint, body, employee, "POST", key)
    if status != 201 or replay != created:
        raise RuntimeError("Real OIDC draft creation did not replay its original result")
    resource = endpoint + "/" + created["id"]
    if request_json(resource, token=foreign)[0] != 404 or request_json(resource, token=administrator)[0] != 403:
        raise RuntimeError("Real OIDC request company/administrator read boundary failed")
    if request_json(resource, token=manager)[0] != 403:
        raise RuntimeError("Real OIDC manager could read a private draft")
    status, submitted = request_json(resource + "/submit", {"version": 0}, employee, "POST", str(uuid.uuid4()))
    if status != 200 or submitted["status"] != "AWAITING_DEPARTMENT_APPROVAL":
        raise RuntimeError("Real OIDC employee could not submit their draft")
    status, history = request_json(resource + "/history", token=manager)
    if status != 200 or len(history) != 2:
        raise RuntimeError("Real OIDC manager could not read submitted request history")
    status, rejected = request_json(resource + "/reject", {"version": 1, "reason": "Integration fixture declined"}, manager, "POST")
    if status != 200 or rejected["status"] != "REJECTED":
        raise RuntimeError("Real OIDC manager could not reject their current step")
    if request_json(resource + "/cancel", {"version": 2}, employee, "POST")[0] != 409:
        raise RuntimeError("A terminal request allowed another transition")
    print("PASS real OIDC draft/replay/submit/manager-reject/history and foreign-company/private-draft/admin denials", flush=True)
    return {"requestId": created["id"], "createReplay": True, "submitRejectHistory": True, "scopeDenials": True}


def check_approval_journey(frontend, employee, manager, finance, foreign, administrator):
    endpoint = frontend + "/api/requests"
    status, actor = request_json(frontend + "/api/me", token=employee)
    if status != 200:
        raise RuntimeError("Approval fixture membership could not be resolved")
    now = datetime.now(timezone.utc)
    months = [now.strftime("%Y-%m"), f"{now.year + (now.month == 12):04d}-{now.month % 12 + 1:02d}"]
    for month in months:
        status, _ = request_json(frontend + "/api/admin/departments/" + actor["departmentId"] + "/budgets/" + month,
                                 {"limitAmount": 5000}, administrator, "PUT")
        if status != 200:
            raise RuntimeError("Approval fixture budget setup failed")
    charged = {month: Decimal("0") for month in months}
    for amount in (999.99, 1000.00, 1000.01, 6000.00):
        status, draft = request_json(endpoint, {"title": "Approval integration fixture", "justification": "Fictional local-only purchase", "amount": amount},
                                     employee, "POST", str(uuid.uuid4()))
        if status != 201:
            raise RuntimeError("Approval fixture creation failed")
        resource = endpoint + "/" + draft["id"]
        if request_json(resource + "/submit", {"version": 0}, employee, "POST", str(uuid.uuid4()))[0] != 200:
            raise RuntimeError("Approval fixture submission failed")
        for token, expected in ((foreign, 404), (employee, 403), (administrator, 403), (finance, 403)):
            if request_json(resource + "/approve", {"version": 1}, token, "POST")[0] != expected:
                raise RuntimeError("Approval command scope/separation boundary failed")
        status, approved = request_json(resource + "/approve", {"version": 1}, manager, "POST")
        if status != 200:
            raise RuntimeError("Department approval failed")
        if request_json(resource + "/approve", {"version": 1}, manager, "POST")[0] != 409:
            raise RuntimeError("Completed department command did not return a safe retry conflict")
        if amount > 1000:
            if approved["status"] != "AWAITING_FINANCE_APPROVAL" or approved["approvedAt"] is not None:
                raise RuntimeError("Department approval finalized an above-threshold purchase")
            if request_json(resource + "/approve", {"version": 2}, manager, "POST")[0] != 403:
                raise RuntimeError("The department actor bypassed finance separation")
            status, approved = request_json(resource + "/approve", {"version": 2}, finance, "POST")
            if amount == 6000:
                if status != 409 or approved.get("code") != "insufficient_budget":
                    raise RuntimeError("Insufficient budget did not prevent final approval")
                continue
            if status != 200:
                raise RuntimeError("Finance approval failed")
        if approved["status"] != "APPROVED" or approved["budgetMonth"] != approved["approvedAt"][:7]:
            raise RuntimeError("Final approval state or UTC charging month is wrong")
        charged[approved["budgetMonth"]] += Decimal(str(amount))
        if request_json(resource + "/cancel", {"version": approved["version"]}, employee, "POST")[0] != 409:
            raise RuntimeError("Approved purchase was cancelled")
    for month, expected_usage in charged.items():
        status, budgets = request_json(frontend + "/api/budgets?month=" + month, token=finance)
        budget = next((value for value in budgets if value["departmentId"] == actor["departmentId"]), None) if status == 200 else None
        if budget is None or Decimal(str(budget["usedAmount"])) != expected_usage:
            raise RuntimeError("Final budget usage differs from successful approval totals")
    print("PASS real OIDC threshold approvals, separate finance actor, retry conflicts and insufficient-budget atomicity", flush=True)
    return {"thresholdRoutes": True, "separateActors": True, "retryConflicts": True, "budgetUsage": True}
