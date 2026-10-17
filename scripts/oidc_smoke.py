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


def request_json(url, body=None, token=None, method=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
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
    admin = login("a-admin")
    foreign = login("b-employee")
    first_status, own = request_json(frontend + "/api/me", token=employee["access_token"])
    foreign_status, other = request_json(frontend + "/api/me", token=foreign["access_token"])
    if first_status != 200 or foreign_status != 200 or own["companyId"] == other["companyId"]:
        raise RuntimeError("Real OIDC identities did not resolve to distinct database companies")
    if request_json(frontend + "/api/admin/memberships", token=employee["access_token"])[0] != 403:
        raise RuntimeError("An employee gained administrator authority")
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
            "codeReplayDenied": True, "revocationDenied": True, "refreshAndLogout": True}
