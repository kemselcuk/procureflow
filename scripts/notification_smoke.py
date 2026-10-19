#!/usr/bin/env python3
"""Durable whole-P4 notification verification on the disposable real stack.

This module is hooked from ``scripts/check-foundation.py`` after the existing provider
checks and reuses its stack context (compose lifecycle, generated passwords, bound
loopback ports and cleanup); it never builds, resets or deletes the stack on its own.

Cases (all against real PostgreSQL, RabbitMQ, Mailpit and real Keycloak
authorization-code + PKCE logins; no fixture inserts into delivery tables):

* AC-NOTIFY worker stop: stop the notification-worker container, create a bounded set
  of real approval events through the live API, prove the transactional outbox is
  pending and no inbox/delivery/in-app/email/mail effect exists while the consumer is
  down, restart the same project/database/broker without any reset and require every
  expected in-app effect and email job to complete within the fixture-defined
  60-second recovery bound, correlated with the actual Mailpit capture (destination,
  stable Message-ID and event/job/correlation headers).
* AC-NOTIFY duplicate: republish one already PUBLISHED canonical event through the
  privileged, row-locked, event-scoped maintenance replay (worker stopped, no live
  lease), then prove the inbox stays unique, read_at/SENT state is preserved, no
  duplicate email is captured and the budget is charged once. The same replay also
  clears the exact canonical raw transient fingerprint and never a broad NULL scope.
* AC-OUTAGE broker: stop RabbitMQ while the application keeps running; a real approval
  commits with state/audit/outbox atomically, dispatch stays unpublished, then the
  broker is restarted on the same container/volume and the worker recovers.
* AC-OUTAGE SMTP: stop Mailpit while broker/consumer stay alive; the email job shows a
  durable PENDING backoff with the fixed ``smtp_unreachable`` code, the in-app effect
  is independent and no business command fails; a Mailpit restart completes the send.
* POISON: publish one bounded malformed message through the RabbitMQ management API and
  require a durable raw-scoped quarantine fingerprint (NULL company/event, fixed code,
  no body anywhere) while valid business events keep progressing.
* REPLAY: with the worker stopped, inject one owner-scoped PostgreSQL fault that fails
  materialization for exactly one canonical event; the bounded transient budget
  (default 1+2+4+8 s, max-attempts 5) must end in a committed terminal diagnostic with
  no business mutation. The fault is removed in ``finally`` and the documented
  row-locked maintenance replay (delete that event's diagnostics plus only the exact
  canonical raw transient record, reset only its dispatch) drives the stable event to
  real in-app + email completion.
* REVOCATION: capture a recipient, stop the worker, deactivate that membership through
  the real admin API and require SKIPPED outcomes without any email send.

Recorded limitations (also in the returned JSON): the raw-scoped poison hash cannot
reconstruct its message for replay; the broker retains the probe until it is consumed;
the SMTP acceptance-before-save duplicate window is covered by the real-service worker
tests, not by this smoke; the 60-second value is a fixture bound, not an SLA.
"""

import csv
import hashlib
import io
import json
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from oidc_smoke import code_session, post_form, request_json

RECOVERY_BOUND_SECONDS = 60
SERVICE_READY_TIMEOUT_SECONDS = 120
FAULT_TERMINAL_TIMEOUT_SECONDS = 120
POISON_QUARANTINE_TIMEOUT_SECONDS = 60
QUEUE_DRAIN_TIMEOUT_SECONDS = 90
MAILPIT_PAGE_LIMIT = 250
CONSUMER_MAX_ATTEMPTS = 5  # worker.consumer.max-attempts default; the terminal diagnostic threshold
COMPANY_B_USERS = ("b-employee", "b-manager", "b-finance", "b-admin")
FOREIGN_USER = "a-manager"
DEMO_DOMAIN = "demo.invalid"

FIXTURE_PLAN = {
    "requests": 7,
    "events": 15,
    "deliveryRows": 34,
    "materializedDeliveries": 32,
    "skippedDeliveries": 2,
    "malformedMessages": 1,
    "ownerScopedFaults": 1,
    "maintenanceReplays": 2,
}

SECRET_NAME_PATTERN = re.compile(r"(?i)(PASSWORD|SECRET|TOKEN)")
SECRET_ASSIGNMENT_PATTERN = re.compile(r"(?i)(PGPASSWORD=)([^\s'\"]+)")
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
BASIC_AUTH_PATTERN = re.compile(r"(?i)\bBasic\s+[A-Za-z0-9+/=]+")
TRANSIENT_PSQL_MARKERS = (
    "could not connect",
    "connection to server",
    "server closed the connection",
    "terminating connection",
    "the database system is starting up",
    "the database system is shutting down",
    "deadlock detected",
    "could not serialize",
    "too many clients",
    "canceling statement due to statement timeout",
    "connection timed out",
    "is not running",
    "no such container",
)


class TransientWaitError(RuntimeError):
    """A bounded retry condition (container/port/SQL connection), never an invariant failure."""


def secret_values(environment):
    return [value for name, value in environment.items()
            if value and SECRET_NAME_PATTERN.search(name) and len(value) >= 6]


def sanitize_text(text, values=()):
    """Never echo a password, bearer token, basic-auth header or PGPASSWORD argument."""
    redacted = str(text)
    for value in values:
        if value:
            redacted = redacted.replace(value, "[REDACTED]")
    redacted = JWT_PATTERN.sub("[REDACTED_TOKEN]", redacted)
    redacted = BASIC_AUTH_PATTERN.sub("Basic [REDACTED]", redacted)
    redacted = SECRET_ASSIGNMENT_PATTERN.sub(r"\1[REDACTED]", redacted)
    return redacted

# Exact operator statements used for the two scoped replays and the temporary fault.
# The maintenance statement is loaded from the documented shared procedure file so the smoke
# always executes the reviewed SQL. Placeholders stay symbolic in the report; the executed
# statement differs only in the resolved company/event UUIDs and is never printed with
# credentials.
MAINTENANCE_REPLAY_SQL_PATH = Path(__file__).resolve().with_name("replay_notification_event.sql")
MAINTENANCE_REPLAY_SQL = MAINTENANCE_REPLAY_SQL_PATH.read_text(encoding="utf-8")

FAULT_SQL = """CREATE OR REPLACE FUNCTION public.p4_verification_materialization_fault() RETURNS trigger
LANGUAGE plpgsql AS $fault$
BEGIN
    IF NEW.event_id = '<event-id>'::uuid THEN
        RAISE EXCEPTION 'p4 stack-verification injected materialization fault' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END
$fault$;
DROP TRIGGER IF EXISTS p4_verification_materialization_fault ON public.notification_delivery;
CREATE TRIGGER p4_verification_materialization_fault BEFORE INSERT ON public.notification_delivery
FOR EACH ROW EXECUTE FUNCTION public.p4_verification_materialization_fault();"""


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def wait_for(description, predicate, timeout_seconds, interval_seconds=1.0, *,
             clock=None, sleep=None):
    """Bounded wait for connectivity/lifecycle observations.

    The deadline is fixed from this call's monotonic origin, and a truthy observation is
    accepted only when the predicate returned at or before that deadline: a success that
    is first observed after the bound fails closed instead of being returned late.

    Only explicitly transient failures are retried. Any other exception, including an
    invariant ``RuntimeError``, propagates immediately instead of being reported later as
    a timeout. Transient error text is never included in the timeout message.
    """
    clock = time.monotonic if clock is None else clock
    sleep = time.sleep if sleep is None else sleep
    started = clock()
    deadline = started + timeout_seconds
    observation = None
    while True:
        try:
            observation = predicate()
        except (TransientWaitError, subprocess.CalledProcessError, urllib.error.URLError,
                OSError) as error:
            observation = {"transientError": type(error).__name__}
            observed_at = clock()
        else:
            observed_at = clock()
            if observation:
                if observed_at > deadline:
                    raise RuntimeError(
                        f"Late success {observed_at - started:.3f}s after the {timeout_seconds}s "
                        f"bound for {description}: the predicate succeeded after the deadline")
                return observation
        if observed_at >= deadline:
            raise RuntimeError(
                f"Timed out after {timeout_seconds}s waiting for {description}: "
                + json.dumps(observation, default=str)[:400])
        sleep(interval_seconds)


def login(stack, username):
    exchange = code_session(stack.provider, stack.frontend, username, stack.environment["DEMO_PASSWORD"])
    status, payload = post_form(stack.provider + "/protocol/openid-connect/token", exchange)
    require(status == 200 and payload and payload.get("access_token"),
            f"real authorization-code login failed for {username} (status {status})")
    return {"username": username, "access_token": payload["access_token"]}


def api_json(stack, token, path, body=None, method=None, key=None):
    return request_json(stack.frontend + path, body, token, method, key)


def sql_uuid(value):
    text = str(value)
    require(re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", text) is not None,
            f"refusing to interpolate a non-UUID value into maintenance SQL: {text!r}")
    return text


def uuid_in(values):
    return ", ".join(f"'{sql_uuid(value)}'::uuid" for value in values)


def mail_message_id(job_id):
    """The exact Message-ID header value the sender sets for one job."""
    return f"<procureflow-job-{job_id}@{DEMO_DOMAIN}>"


def mail_message_key(job_id):
    """Mailpit lists Message-IDs without the surrounding angle brackets."""
    return f"procureflow-job-{job_id}@{DEMO_DOMAIN}"


def raw_fingerprint(body):
    """Mirror of ConsumerFailureFingerprint.forRawBody for the fixed malformed_message code."""
    inner = hashlib.sha256(body.encode("utf-8")).hexdigest()
    return hashlib.sha256(("p4c:v1:raw:malformed_message:" + inner).encode("ascii")).hexdigest()


def mail_headers(headers):
    """Normalize Mailpit's header map (name -> list of values) for case-insensitive lookup."""
    return {str(name).lower(): (value if isinstance(value, list) else [value])
            for name, value in (headers or {}).items()}


def mail_addresses(value):
    addresses = []
    for entry in value or []:
        if isinstance(entry, dict):
            addresses.append(entry.get("Address") or entry.get("address") or "")
        else:
            addresses.append(str(entry))
    return [address for address in addresses if address]


def iso_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def elapsed_iso(start, end):
    return round((datetime.fromisoformat(end) - datetime.fromisoformat(start)).total_seconds(), 3)


class NotificationStack:
    """Compose/PostgreSQL/broker/Mailpit facade built by scripts/check-foundation.py."""

    def __init__(self, *, root, compose_argv, environment, project, frontend, provider,
                 service_address, base_url, rabbitmq_request, mailpit_api, log=print):
        self.root = root
        self.compose_argv = list(compose_argv)
        self.environment = environment
        self.project = project
        self.frontend = frontend
        self.provider = provider
        self.service_address = service_address
        self.base_url = base_url
        self.rabbitmq_request = rabbitmq_request
        self.mailpit_api = mailpit_api
        self.log = log
        self.secrets = secret_values(environment)

    # ---- process and PostgreSQL helpers ----

    def compose(self, *arguments, capture=False, input_text=None):
        return subprocess.run([*self.compose_argv, *arguments], cwd=self.root, env=self.environment,
                              check=True, capture_output=capture, text=True, input=input_text)

    def _sql(self, user, password_variable, sql):
        # The password stays a shell variable name, never its value, in the command text.
        command = (f'PGPASSWORD="${password_variable}" psql -U {user} -d procureflow -X -q '
                   f'-v ON_ERROR_STOP=1 --csv -t')
        try:
            result = self.compose("exec", "-T", "postgres", "bash", "-c", command,
                                  capture=True, input_text=sql)
        except subprocess.CalledProcessError as error:
            summary = self._sql_error_summary(error.stderr)
            if any(marker in summary.lower() for marker in TRANSIENT_PSQL_MARKERS):
                raise TransientWaitError(f"psql as {user} transient failure: {summary}") from None
            raise RuntimeError(f"psql as {user} failed: {summary}") from None
        return list(csv.reader(io.StringIO(result.stdout)))

    def _sql_error_summary(self, stderr):
        """One sanitized error line; never DETAIL/CONTEXT rows or the full compose stderr."""
        lines = [line.strip() for line in (stderr or "").splitlines() if line.strip()]
        selected = next((line for line in lines if "ERROR:" in line or "FATAL:" in line),
                        lines[0] if lines else "unknown error")
        return sanitize_text(selected, self.secrets)[:240]

    def worker_sql(self, sql):
        """Restricted worker login cross-checks; the password stays an in-container env name."""
        return self._sql("procureflow_worker", "WORKER_DB_PASSWORD", sql)

    def owner_sql(self, sql):
        """Owner (container superuser) statements: temporary fault setup and the scoped replays only."""
        return self._sql("procureflow", "POSTGRES_PASSWORD", sql)

    # ---- container lifecycle and readiness ----

    def stop_worker(self):
        self.compose("stop", "notification-worker")

    def start_worker(self):
        self.compose("start", "notification-worker")
        return self.wait_worker_ready()

    def wait_worker_ready(self, timeout_seconds=SERVICE_READY_TIMEOUT_SECONDS):
        def probe():
            try:
                url = self.base_url("notification-worker", 8081) + "/actuator/health/readiness"
                with urllib.request.urlopen(url, timeout=10) as response:
                    body = json.load(response)
                return response.status == 200 and body.get("status") == "UP"
            except (subprocess.CalledProcessError, urllib.error.URLError, OSError, ValueError):
                return False

        wait_for("notification-worker readiness", probe, timeout_seconds)
        return time.monotonic()

    def wait_rabbitmq_ready(self, timeout_seconds=SERVICE_READY_TIMEOUT_SECONDS):
        wait_for("RabbitMQ management readiness", lambda: self.rabbitmq_request("overview"),
                 timeout_seconds, interval_seconds=2.0)
        return time.monotonic()

    def wait_mailpit_ready(self, timeout_seconds=SERVICE_READY_TIMEOUT_SECONDS):
        def probe():
            try:
                info = self.mailpit_api("/api/v1/info")
                return info.get("Version") == "v1.31.6"
            except (subprocess.CalledProcessError, urllib.error.URLError, OSError, ValueError):
                return False

        wait_for("Mailpit readiness", probe, timeout_seconds)
        return time.monotonic()

    def worker_logs(self, since_seconds):
        result = self.compose("logs", "--no-color", "--since", f"{int(since_seconds)}s",
                              "notification-worker", capture=True)
        return result.stdout or ""

    # ---- Mailpit capture helpers ----

    def mailpit_messages(self):
        messages = []
        start = 0
        for _ in range(10):
            payload = self.mailpit_api(f"/api/v1/messages?limit={MAILPIT_PAGE_LIMIT}&start={start}")
            page = payload.get("messages") or []
            messages.extend(page)
            start += len(page)
            if len(page) < MAILPIT_PAGE_LIMIT:
                break
        return messages

    def mailpit_message(self, message_id):
        return self.mailpit_api("/api/v1/message/" + urllib.parse.quote(str(message_id)))
    def mailpit_message_headers(self, message_id):
        return self.mailpit_api("/api/v1/message/" + urllib.parse.quote(str(message_id)) + "/headers")

    def mailpit_message_counts(self):
        """Occurrence count per Mailpit Message-ID; a duplicate delivery would exceed one."""
        counts = {}
        for summary in self.mailpit_messages():
            message_id = summary.get("MessageID") or summary.get("messageId")
            counts[message_id] = counts.get(message_id, 0) + 1
        return counts

    # ---- owner-scoped maintenance statements ----

    def install_materialization_fault(self, event_id):
        event = sql_uuid(event_id)
        self.owner_sql(FAULT_SQL.replace("<event-id>", event))

    def remove_materialization_fault(self):
        self.owner_sql("DROP TRIGGER IF EXISTS p4_verification_materialization_fault "
                       "ON public.notification_delivery;\n"
                       "DROP FUNCTION IF EXISTS public.p4_verification_materialization_fault();")

    def fault_present(self):
        triggers = self.owner_sql("SELECT count(*) FROM pg_trigger "
                                  "WHERE tgname = 'p4_verification_materialization_fault';")
        functions = self.owner_sql("SELECT count(*) FROM pg_proc "
                                   "WHERE proname = 'p4_verification_materialization_fault';")
        return triggers[0][0] != "0" or functions[0][0] != "0"

    def maintenance_replay(self, company_id, event_id):
        sql = (MAINTENANCE_REPLAY_SQL.replace("<company-id>", sql_uuid(company_id))
               .replace("<event-id>", sql_uuid(event_id)))
        self.owner_sql(sql)


@dataclass(frozen=True)
class CompletionObservation:
    """One accepted completion read: bounded elapsed, monotonic instant and wall label."""
    seconds: float
    completed_at_monotonic: float
    completed_at: str


class Fixture:
    """Company-B fixture driver: real API business commands, restricted worker DB reads."""

    def __init__(self, stack, tokens):
        self.stack = stack
        self.tokens = tokens
        self.company_id = None
        self.department_id = None
        self.request_ids = []
        self.event_ids = []
        self.identities = {}

    # ---- identity and budget setup ----

    def identity_id(self, username):
        if username not in self.identities:
            self.identities[username] = self.resolve_self(username)["identityId"]
        return self.identities[username]

    def resolve_self(self, username):
        status, me = api_json(self.stack, self.tokens[username]["access_token"], "/api/me")
        require(status == 200 and me and me.get("companyId"), f"/api/me failed for {username} ({status})")
        return me

    def set_company_budgets(self, limit=5000):
        me = self.resolve_self("b-employee")
        self.company_id = me["companyId"]
        self.department_id = me["departmentId"]
        now = datetime.now(timezone.utc)
        months = [now.strftime("%Y-%m"), f"{now.year + (now.month == 12):04d}-{now.month % 12 + 1:02d}"]
        for month in months:
            status, _ = api_json(self.stack, self.tokens["b-admin"]["access_token"],
                                 f"/api/admin/departments/{self.department_id}/budgets/{month}",
                                 {"limitAmount": limit}, "PUT")
            require(status == 200, f"company B budget setup failed for {month} ({status})")
        return {"month": months[0], "months": months, "limit": limit}

    def budget_usage(self):
        month = datetime.now(timezone.utc).strftime("%Y-%m")
        status, budgets = api_json(self.stack, self.tokens["b-finance"]["access_token"],
                                   "/api/budgets?month=" + month)
        require(status == 200 and budgets is not None, f"budget read failed ({status})")
        budget = next((entry for entry in budgets if entry.get("departmentId") == self.department_id), None)
        require(budget is not None, "company B engineering budget row is missing")
        return str(budget["usedAmount"])

    def membership(self, username):
        status, memberships = api_json(self.stack, self.tokens["b-admin"]["access_token"],
                                       "/api/admin/memberships")
        require(status == 200 and memberships is not None, f"membership read failed ({status})")
        me = self.resolve_self(username)
        record = next((entry for entry in memberships if entry.get("id") == me["membershipId"]), None)
        require(record is not None, f"membership record missing for {username}")
        return record

    # ---- real business commands ----

    def create(self, amount, title):
        key = str(uuid.uuid4())
        body = {"title": title, "justification": "Fictional local-only P4 stack verification fixture",
                "amount": amount}
        status, draft = api_json(self.stack, self.tokens["b-employee"]["access_token"],
                                 "/api/requests", body, "POST", key)
        require(status == 201 and draft and draft.get("status") == "DRAFT",
                f"company B draft creation failed ({status})")
        self.request_ids.append(draft["id"])
        return draft

    def submit(self, request_id, version):
        status, view = api_json(self.stack, self.tokens["b-employee"]["access_token"],
                                f"/api/requests/{request_id}/submit", {"version": version},
                                "POST", str(uuid.uuid4()))
        require(status == 200 and view, f"company B submit failed ({status})")
        return view

    def approve(self, actor, request_id, version):
        status, view = api_json(self.stack, self.tokens[actor]["access_token"],
                                f"/api/requests/{request_id}/approve", {"version": version}, "POST")
        require(status == 200 and view, f"{actor} approval failed ({status})")
        return view

    def create_and_approve(self, amount, route, title):
        draft = self.create(amount, title)
        view = self.submit(draft["id"], draft["version"])
        view = self.approve("b-manager", draft["id"], view["version"])
        if route == "finance":
            require(view["status"] == "AWAITING_FINANCE_APPROVAL",
                    f"above-threshold request did not require finance ({view['status']})")
            view = self.approve("b-finance", draft["id"], view["version"])
        require(view["status"] == "APPROVED", f"request did not reach APPROVED ({view['status']})")
        return draft["id"], view

    # ---- canonical event/recipient cross-checks through the worker login ----

    def events_for(self, request_ids):
        rows = self.stack.worker_sql(
            "SELECT id, request_id, event_type, correlation_id, company_id, occurred_at::text FROM outbox_event "
            f"WHERE request_id IN ({uuid_in(request_ids)}) ORDER BY occurred_at, id")
        events = [dict(zip(("id", "request_id", "event_type", "correlation_id", "company_id", "occurred_at"),
                           row)) for row in rows]
        for event in events:
            if event["id"] not in self.event_ids:
                self.event_ids.append(event["id"])
        if events:
            self.company_id = events[0]["company_id"]
        return events

    def snapshot(self, event_ids):
        ids = uuid_in(event_ids)
        events = self.stack.worker_sql(
            "SELECT id, request_id, event_type, correlation_id, company_id, occurred_at::text FROM outbox_event "
            f"WHERE id IN ({ids}) ORDER BY occurred_at, id")
        inbox = self.stack.worker_sql(
            f"SELECT event_id, processed_at::text FROM notification_inbox WHERE event_id IN ({ids})")
        deliveries = self.stack.worker_sql(
            "SELECT event_id, identity_id, channel, outcome, coalesce(reason_code, '') FROM notification_delivery "
            f"WHERE event_id IN ({ids}) ORDER BY event_id, identity_id, channel")
        in_app = self.stack.worker_sql(
            "SELECT event_id, identity_id, id, coalesce(read_at::text, '') FROM in_app_notification "
            f"WHERE event_id IN ({ids}) ORDER BY event_id, identity_id")
        email_jobs = self.stack.worker_sql(
            "SELECT id, event_id, identity_id, recipient_address, state, attempts, "
            "coalesce(error_code, ''), coalesce(sent_at::text, '') FROM email_job "
            f"WHERE event_id IN ({ids}) ORDER BY event_id, identity_id")
        dispatch = self.stack.worker_sql(
            "SELECT event_id, state, attempts, coalesce(published_at::text, ''), coalesce(error_code, '') "
            f"FROM outbox_dispatch WHERE event_id IN ({ids})")
        return {
            "events": [dict(zip(("id", "request_id", "event_type", "correlation_id", "company_id",
                                 "occurred_at"), row)) for row in events],
            "inbox": {row[0]: row[1] for row in inbox},
            "deliveries": [dict(zip(("event_id", "identity_id", "channel", "outcome", "reason_code"), row))
                           for row in deliveries],
            "in_app": [dict(zip(("event_id", "identity_id", "id", "read_at"), row)) for row in in_app],
            "email_jobs": [dict(zip(("id", "event_id", "identity_id", "recipient_address", "state",
                                     "attempts", "error_code", "sent_at"), row)) for row in email_jobs],
            "dispatch": {row[0]: dict(zip(("state", "attempts", "published_at", "error_code"), row[1:]))
                         for row in dispatch},
        }

    def expected_effects(self, event_ids):
        """Derive expected per-event effects from the captured canonical recipient snapshot
        and the current membership state; never assume a fixed count per event."""
        ids = uuid_in(event_ids)
        events = {row[0]: row[1] for row in self.stack.worker_sql(
            f"SELECT id, company_id, event_type FROM outbox_event WHERE id IN ({ids})")}
        recipients = self.stack.worker_sql(
            "SELECT event_id, identity_id, membership_id, in_app, email FROM outbox_recipient "
            f"WHERE event_id IN ({ids}) ORDER BY event_id, identity_id")
        membership_ids = sorted({row[2] for row in recipients})
        memberships = {}
        if membership_ids:
            memberships = {row[0]: {"company_id": row[1], "identity_id": row[2], "active": row[3]}
                           for row in self.stack.worker_sql(
                               "SELECT id, company_id, identity_id, active FROM membership "
                               f"WHERE id IN ({uuid_in(membership_ids)})")}
        expected = {event_id: {"in_app": 0, "email": 0, "skipped": 0} for event_id in event_ids}
        for event_id, identity_id, membership_id, in_app, email in recipients:
            membership = memberships.get(membership_id)
            eligible = (membership is not None and membership["active"] == "t"
                        and membership["identity_id"] == identity_id
                        and events.get(event_id) == membership["company_id"])
            for channel in (("IN_APP", in_app), ("EMAIL", email)):
                if channel[1] != "t":
                    continue
                if eligible:
                    expected[event_id]["in_app" if channel[0] == "IN_APP" else "email"] += 1
                else:
                    expected[event_id]["skipped"] += 1
        return expected

    def completion_problems(self, snapshot, expected, event_ids):
        problems = []
        for event_id in event_ids:
            want = expected[event_id]
            if event_id not in snapshot["inbox"]:
                problems.append(f"{event_id}: no committed inbox marker")
            deliveries = [row for row in snapshot["deliveries"] if row["event_id"] == event_id]
            materialized = [row for row in deliveries if row["outcome"] == "MATERIALIZED"]
            skipped = [row for row in deliveries if row["outcome"] == "SKIPPED"]
            if len(materialized) != want["in_app"] + want["email"]:
                problems.append(f"{event_id}: {len(materialized)} materialized deliveries, "
                                f"expected {want['in_app'] + want['email']}")
            if len(skipped) != want["skipped"]:
                problems.append(f"{event_id}: {len(skipped)} skipped deliveries, expected {want['skipped']}")
            in_app = [row for row in snapshot["in_app"] if row["event_id"] == event_id]
            if len(in_app) != want["in_app"]:
                problems.append(f"{event_id}: {len(in_app)} in-app rows, expected {want['in_app']}")
            jobs = [job for job in snapshot["email_jobs"] if job["event_id"] == event_id]
            sent = [job for job in jobs if job["state"] == "SENT"]
            if len(jobs) != want["email"] or len(sent) != want["email"]:
                problems.append(f"{event_id}: {len(sent)}/{len(jobs)} sent email jobs, "
                                f"expected {want['email']}")
            dispatch = snapshot["dispatch"].get(event_id)
            if not dispatch or dispatch["state"] != "PUBLISHED" or not dispatch["published_at"]:
                problems.append(f"{event_id}: dispatch not published: {dispatch}")
        return problems

    def wait_completed(self, event_ids, expected, timeout_seconds, started_at=None, *,
                       clock=None, sleep=None):
        """Bounded whole-fixture completion check.

        ``started_at`` is the caller's monotonic origin (for example the worker readiness
        instant); the deadline is always ``started_at + timeout_seconds``, so a late call
        never gets extra budget and the returned elapsed time is never relabeled. A
        completion read that finishes after the deadline fails closed instead of being
        accepted as a success.
        """
        clock = time.monotonic if clock is None else clock
        sleep = time.sleep if sleep is None else sleep
        started = clock() if started_at is None else started_at
        deadline = started + timeout_seconds
        problems = ["no snapshot yet"]
        while True:
            try:
                snapshot = self.snapshot(event_ids)
            except TransientWaitError as error:
                # A container/psql connection hiccup is retryable; an invariant failure is not.
                problems = [f"transient psql read: {type(error).__name__}"]
                snapshot = None
            observed_at = clock()
            if snapshot is not None:
                problems = self.completion_problems(snapshot, expected, event_ids)
                if not problems:
                    if observed_at > deadline:
                        raise RuntimeError(
                            f"completion observed {observed_at - started:.3f}s after the "
                            f"{timeout_seconds}s bound origin, not accepting it as success")
                    return CompletionObservation(round(observed_at - started, 3),
                                                 observed_at, iso_now())
            if observed_at >= deadline:
                raise RuntimeError(f"fixture completion exceeded {timeout_seconds}s: "
                                   + "; ".join(problems[:8]))
            sleep(1.0)

    # ---- Mailpit correlation ----

    def messages_to_identities(self, identity_ids):
        wanted = {identity + "@" + DEMO_DOMAIN for identity in identity_ids}
        found = []
        for summary in self.stack.mailpit_messages():
            addresses = set(mail_addresses(summary.get("To")))
            if addresses & wanted:
                found.append({"id": summary.get("ID") or summary.get("id"),
                              "messageId": summary.get("MessageID") or summary.get("messageId"),
                              "to": sorted(addresses)})
        return found

    def verify_no_mailpit_messages(self, event_ids):
        snapshot = self.snapshot(event_ids)
        identity_ids = sorted({row["identity_id"] for row in snapshot["deliveries"]}
                              | {row["identity_id"] for row in snapshot["in_app"]})
        if not identity_ids:
            recipients = self.stack.worker_sql(
                "SELECT DISTINCT identity_id FROM outbox_recipient "
                f"WHERE event_id IN ({uuid_in(event_ids)})")
            identity_ids = sorted(row[0] for row in recipients)
        return not self.messages_to_identities(identity_ids)

    def verify_mailpit(self, event_ids):
        snapshot = self.snapshot(event_ids)
        events = {event["id"]: event for event in snapshot["events"]}
        jobs = [job for job in snapshot["email_jobs"] if job["state"] == "SENT"]
        wanted = {mail_message_key(job["id"]): job for job in jobs}
        by_message_id = {}
        for summary in self.stack.mailpit_messages():
            message_id = summary.get("MessageID") or summary.get("messageId")
            by_message_id.setdefault(message_id, []).append(summary)
        missing = [message_id for message_id in wanted if message_id not in by_message_id]
        require(not missing, f"Mailpit is missing {len(missing)} expected fixture capture(s)")
        duplicated = [message_id for message_id in wanted if len(by_message_id[message_id]) > 1]
        require(not duplicated, f"Mailpit captured {len(duplicated)} duplicate fixture message(s)")
        verified = []
        for message_id, job in sorted(wanted.items(), key=lambda item: item[1]["event_id"]):
            summary = by_message_id[message_id][0]
            headers = mail_headers(self.stack.mailpit_message_headers(summary.get("ID") or summary.get("id")))
            addresses = mail_addresses(summary.get("To"))
            require(addresses == [job["identity_id"] + "@" + DEMO_DOMAIN],
                    f"captured destination {addresses} does not match job recipient for {job['id']}")
            require(all(address.endswith("@" + DEMO_DOMAIN) for address in addresses),
                    "fixture mail was addressed outside demo.invalid")
            from_addresses = mail_addresses([summary.get("From")])
            require(from_addresses == ["notifications@" + DEMO_DOMAIN],
                    f"captured From {from_addresses} is not the fixed notification sender")
            require(not (summary.get("Cc") or summary.get("Bcc")),
                    f"captured fixture message for {job['id']} unexpectedly has Cc/Bcc")
            require((headers.get("message-id") or [""])[0] == mail_message_id(job["id"]),
                    f"Mailpit Message-ID mismatch for {job['id']}: "
                    f"observed {headers.get('message-id')!r}")
            require((headers.get("x-procureflow-job-id") or [""])[0] == job["id"],
                    f"X-ProcureFlow-Job-Id mismatch for {job['id']}")
            require((headers.get("x-procureflow-event-id") or [""])[0] == job["event_id"],
                    f"X-ProcureFlow-Event-Id mismatch for {job['id']}")
            require((headers.get("x-procureflow-correlation-id") or [""])[0]
                    == events[job["event_id"]]["correlation_id"],
                    f"X-ProcureFlow-Correlation-Id mismatch for {job['id']}")
            require(summary.get("Subject")
                    == "ProcureFlow notification: " + events[job["event_id"]]["event_type"],
                    f"captured subject mismatch for {job['id']}")
            detail = self.stack.mailpit_message(summary.get("ID") or summary.get("id"))
            body_text = detail.get("Text") or detail.get("text") or ""
            request_id = events[job["event_id"]]["request_id"]
            require("Request ID: " + request_id in body_text,
                    f"captured body does not carry the canonical request id for {job['id']}")
            require("Correlation ID: " + events[job["event_id"]]["correlation_id"] in body_text,
                    f"captured body does not carry the canonical correlation id for {job['id']}")
            verified.append({"messageId": mail_message_id(job["id"]), "job": job["id"],
                             "event": job["event_id"], "request": request_id,
                             "identity": job["identity_id"], "to": addresses})
        return {"capturedMessages": len(wanted), "verified": verified}

    def verify_all_captured_addresses(self):
        """Every actual Mailpit address is a local demo.invalid identity and every Message-ID
        has the stable per-job shape; this also covers P1-P3 notification mail in the same stack."""
        messages = self.stack.mailpit_messages()
        pattern = re.compile(r"^procureflow-job-[0-9a-f-]{36}@demo\.invalid$")
        for summary in messages:
            addresses = mail_addresses(summary.get("To"))
            require(addresses and all(address.endswith("@" + DEMO_DOMAIN) for address in addresses),
                    f"captured mail outside demo.invalid: {addresses}")
            message_id = summary.get("MessageID") or summary.get("messageId") or ""
            require(pattern.fullmatch(message_id) is not None,
                    f"captured Message-ID does not have the stable job shape: {message_id!r}")
        return {"totalCapturedMessages": len(messages),
                "allAddressesLocalDemoDomain": True,
                "allMessageIdsStable": True}

    def captured_recipient_channels(self, event_ids):
        """Captured recipient rows with their enabled channels, per event (never mutable state)."""
        rows = self.stack.worker_sql(
            "SELECT event_id, identity_id, membership_id, in_app, email FROM outbox_recipient "
            f"WHERE event_id IN ({uuid_in(event_ids)}) ORDER BY event_id, identity_id")
        captured = {event_id: [] for event_id in event_ids}
        for event_id, identity_id, membership_id, in_app, email in rows:
            channels = [name for name, flag in (("IN_APP", in_app), ("EMAIL", email)) if flag == "t"]
            captured[event_id].append((identity_id, membership_id, channels))
        return captured

    def final_consistency_problems(self, snapshot, event_ids):
        """Time-independent end-state invariants: every captured recipient/channel has exactly one
        delivery row, every MATERIALIZED delivery has its matching in-app/email effect (all email
        jobs SENT), SKIPPED deliveries have no effect, and every event is committed and published."""
        captured = self.captured_recipient_channels(event_ids)
        problems = []
        for event_id in event_ids:
            if event_id not in snapshot["inbox"]:
                problems.append(f"{event_id}: no committed inbox marker")
            deliveries = {(row["identity_id"], row["channel"]): row
                          for row in snapshot["deliveries"] if row["event_id"] == event_id}
            expected_rows = 0
            for identity, membership, channels in captured[event_id]:
                expected_rows += len(channels)
                for channel in channels:
                    if (identity, channel) not in deliveries:
                        problems.append(f"{event_id}: no {channel} delivery for captured recipient {identity}")
            if len(deliveries) != expected_rows:
                problems.append(f"{event_id}: {len(deliveries)} delivery rows, captured {expected_rows}")
            in_app = {row["identity_id"] for row in snapshot["in_app"] if row["event_id"] == event_id}
            email = {job["identity_id"]: job for job in snapshot["email_jobs"] if job["event_id"] == event_id}
            for (identity, channel), delivery in deliveries.items():
                if channel == "IN_APP":
                    if delivery["outcome"] == "MATERIALIZED" and identity not in in_app:
                        problems.append(f"{event_id}: materialized IN_APP delivery without in-app row")
                    if delivery["outcome"] == "SKIPPED" and identity in in_app:
                        problems.append(f"{event_id}: skipped IN_APP delivery with an in-app row")
                else:
                    job = email.get(identity)
                    if delivery["outcome"] == "MATERIALIZED" and (job is None or job["state"] != "SENT"):
                        problems.append(f"{event_id}: materialized EMAIL delivery without a SENT job")
                    if delivery["outcome"] == "SKIPPED" and job is not None:
                        problems.append(f"{event_id}: skipped EMAIL delivery with an email job")
            dispatch = snapshot["dispatch"].get(event_id)
            if not dispatch or dispatch["state"] != "PUBLISHED" or not dispatch["published_at"]:
                problems.append(f"{event_id}: dispatch not published: {dispatch}")
        return problems

    # ---- API cross-checks ----

    def notification_page(self, username, size=100):
        status, page = api_json(self.stack, self.tokens[username]["access_token"],
                                f"/api/notifications?size={size}")
        require(status == 200 and page is not None, f"notification list failed for {username} ({status})")
        return page

    def wait_terminal_diagnostic(self, event_id, timeout_seconds):
        def probe():
            rows = self.stack.worker_sql(
                "SELECT payload_hash, coalesce(company_id::text, ''), coalesce(event_id::text, ''), "
                "channel, error_code, attempts FROM notification_diagnostic "
                f"WHERE event_id = '{sql_uuid(event_id)}'::uuid")
            if not rows:
                return None
            attempts = int(rows[0][5])
            return rows if attempts >= CONSUMER_MAX_ATTEMPTS else None

        return wait_for(f"terminal diagnostic for event {event_id}", probe, timeout_seconds)


def check_notification_pipeline(stack):
    """Run every notification case and return the JSON report section."""
    cases = []
    tokens = {username: login(stack, username) for username in (*COMPANY_B_USERS, FOREIGN_USER)}
    stack.log("PASS real authorization-code + PKCE logins for the notification fixtures", flush=True)
    fixture = Fixture(stack, tokens)
    budget = fixture.set_company_budgets()
    stack.log(f"PASS company B engineering budget prepared ({budget['months']}, limit {budget['limit']})",
              flush=True)

    # ------------------------------------------------------------------ AC-NOTIFY
    notify_started_at = iso_now()
    stack.stop_worker()
    notify_requests = [
        fixture.create_and_approve(50, "department", "P4 notify single-step fixture")[0],
        fixture.create_and_approve(1500, "finance", "P4 notify two-step fixture")[0],
    ]
    notify_events = fixture.events_for(notify_requests)
    require(len(notify_events) == 5, f"notify fixture committed {len(notify_events)} events, expected 5")
    notify_ids = [event["id"] for event in notify_events]
    expected_notify = fixture.expected_effects(notify_ids)
    down = fixture.snapshot(notify_ids)
    require(not down["inbox"] and not down["deliveries"] and not down["in_app"] and not down["email_jobs"],
            "consumer effects exist while the notification worker is stopped")
    for event in notify_events:
        dispatch = down["dispatch"].get(event["id"])
        require(dispatch and dispatch["state"] == "PENDING" and dispatch["published_at"] == ""
                and dispatch["attempts"] == "0",
                f"dispatch for {event['id']} is not transactional-pending while the worker is stopped: "
                f"{dispatch}")
    require(fixture.verify_no_mailpit_messages(notify_ids),
            "Mailpit captured fixture mail while the worker was stopped")
    stack.log("PASS worker stopped: outbox pending, no inbox/delivery/in-app/email/mail effect", flush=True)

    notify_restart_requested = time.monotonic()
    notify_restart_requested_at = iso_now()
    ready_at = stack.start_worker()
    notify_readiness_at = iso_now()
    notify_observation = fixture.wait_completed(notify_ids, expected_notify, RECOVERY_BOUND_SECONDS,
                                                ready_at)
    notify_mailpit = fixture.verify_mailpit(notify_ids)
    notify_completed_at = iso_now()
    notify_expected_in_app = sum(entry["in_app"] for entry in expected_notify.values())
    notify_expected_email = sum(entry["email"] for entry in expected_notify.values())
    cases.append({
        "case": "AC-NOTIFY/worker-stop-backlog",
        "startedAt": notify_started_at, "workerRestartRequestedAt": notify_restart_requested_at,
        "workerReadinessAt": notify_readiness_at,
        "completedAt": notify_completed_at,
        "completionObservedAt": notify_observation.completed_at,
        "elapsedFromStopSeconds": elapsed_iso(notify_started_at, notify_completed_at),
        "workerRestartToReadinessSeconds": round(ready_at - notify_restart_requested, 3),
        "restartToCompletionSeconds": round(
            notify_observation.completed_at_monotonic - notify_restart_requested, 3),
        "windowStart": notify_started_at, "recoveryBoundSeconds": RECOVERY_BOUND_SECONDS,
        "observedRecoverySeconds": notify_observation.seconds,
        "events": [{"eventId": event["id"], "requestId": event["request_id"],
                    "eventType": event["event_type"], "correlationId": event["correlation_id"],
                    "expectedInApp": expected_notify[event["id"]]["in_app"],
                    "expectedEmail": expected_notify[event["id"]]["email"]} for event in notify_events],
        "expectedInApp": notify_expected_in_app, "expectedEmail": notify_expected_email,
        "mailpit": notify_mailpit,
    })
    stack.log(f"PASS AC-NOTIFY: {notify_expected_in_app} in-app effects and {notify_expected_email} "
              f"emails completed {notify_observation.seconds:.1f}s after worker readiness "
              f"(bound {RECOVERY_BOUND_SECONDS}s); Mailpit correlated", flush=True)

    # ------------------------------------------------- AC-NOTIFY duplicate replay
    replay_event = next(event for event in notify_events
                        if event["event_type"] == "request.approved"
                        and event["request_id"] == notify_requests[1])
    replay_id = replay_event["id"]
    page = fixture.notification_page("b-employee")
    item = next((entry for entry in page["items"] if entry["eventId"] == replay_id), None)
    require(item is not None, "b-employee notification list is missing the replay event")
    status, first_read = api_json(stack, tokens["b-employee"]["access_token"],
                                  f"/api/notifications/{item['id']}/read", method="POST")
    require(status == 200 and first_read and first_read.get("readAt"), "own read marking failed")
    status, second_read = api_json(stack, tokens["b-employee"]["access_token"],
                                   f"/api/notifications/{item['id']}/read", method="POST")
    require(status == 200 and second_read.get("readAt") == first_read["readAt"],
            "repeat read did not preserve the first read time")
    foreign_page = fixture.notification_page(FOREIGN_USER)
    require(not any(entry["eventId"] in notify_ids for entry in foreign_page["items"]),
            "a foreign-company identity can see fixture notifications")
    require(api_json(stack, tokens[FOREIGN_USER]["access_token"],
                     f"/api/notifications/{item['id']}/read", method="POST")[0] == 404,
            "a foreign-company read did not return 404")

    pre_replay = fixture.snapshot([replay_id])
    require(pre_replay["dispatch"][replay_id]["state"] == "PUBLISHED",
            "the duplicate-replay event is not PUBLISHED before the operator replay")
    usage_before = fixture.budget_usage()
    stack.stop_worker()
    duplicate_maintenance_at = iso_now()
    stack.maintenance_replay(fixture.company_id, replay_id)
    reset = fixture.snapshot([replay_id])
    require(reset["dispatch"][replay_id]["state"] == "PENDING"
            and reset["dispatch"][replay_id]["attempts"] == "0"
            and reset["dispatch"][replay_id]["published_at"] == "",
            "the operator replay did not reset the scoped dispatch row")
    require(not fixture.stack.worker_sql(
        "SELECT 1 FROM notification_diagnostic WHERE event_id = "
        f"'{sql_uuid(replay_id)}'::uuid LIMIT 1"),
        "the duplicate replay left diagnostics for the replayed event")
    ready_at = stack.start_worker()
    duplicate_readiness_at = iso_now()
    wait_for("duplicate event republished", lambda: (
        fixture.snapshot([replay_id])["dispatch"][replay_id]["state"] == "PUBLISHED"), 60)
    wait_for("duplicate suppression log line", lambda: (
        f"Suppressed duplicate delivery event={replay_id}" in stack.worker_logs(180)), 60)
    post_replay = fixture.snapshot([replay_id])
    pre_in_app = [row for row in pre_replay["in_app"] if row["event_id"] == replay_id]
    post_in_app = [row for row in post_replay["in_app"] if row["event_id"] == replay_id]
    require(len(post_replay["inbox"]) == 1, "duplicate replay changed the inbox uniqueness")
    require(post_replay["deliveries"] == pre_replay["deliveries"] and pre_replay["deliveries"],
            "duplicate replay changed delivery rows")
    require(post_in_app == pre_in_app and pre_in_app and pre_in_app[0]["read_at"] != "",
            "duplicate replay changed the persisted read state")
    require(post_replay["email_jobs"] == pre_replay["email_jobs"]
            and all(job["state"] == "SENT" for job in post_replay["email_jobs"]),
            "duplicate replay changed the SENT email-job state")
    counts = fixture.stack.mailpit_message_counts()
    replayed_message_ids = [mail_message_key(job["id"]) for job in pre_replay["email_jobs"]]
    require(replayed_message_ids and all(counts.get(message_id) == 1
                                         for message_id in replayed_message_ids),
            "duplicate replay produced an extra Mailpit message")
    require(fixture.budget_usage() == usage_before,
            "duplicate replay changed the charged budget usage")
    duplicate_completed_at = iso_now()
    cases.append({
        "case": "AC-NOTIFY/duplicate-replay",
        "maintenanceStartedAt": duplicate_maintenance_at, "workerReadinessAt": duplicate_readiness_at,
        "completedAt": duplicate_completed_at,
        "elapsedFromMaintenanceSeconds": elapsed_iso(duplicate_maintenance_at, duplicate_completed_at),
        "eventId": replay_id, "notificationId": item["id"],
        "readPreserved": True, "uniqueInbox": 1,
        "materializedDeliveries": len(pre_replay["deliveries"]),
        "sentEmailJobs": len(pre_replay["email_jobs"]),
        "capturedMessagesForEvent": len(replayed_message_ids), "budgetUnchanged": usage_before,
        "foreignDenials": {"listScoped": True, "read": 404},
    })
    stack.log("PASS AC-NOTIFY duplicate: one inbox, preserved read/SENT state, no second email, "
              "no second budget charge", flush=True)

    # ------------------------------------------------------------ AC-OUTAGE broker
    broker_stopped_at = iso_now()
    stack.compose("stop", "rabbitmq")
    wait_for("RabbitMQ management down", lambda: (
        _rabbitmq_unreachable(stack)), 30, interval_seconds=2.0)
    command_started = time.monotonic()
    broker_command_started_at = iso_now()
    outage_request = fixture.create_and_approve(75, "department", "P4 broker-outage fixture")[0]
    command_seconds = time.monotonic() - command_started
    broker_command_completed_at = iso_now()
    outage_events = fixture.events_for([outage_request])
    require(len(outage_events) == 2, "broker-outage fixture did not commit exactly two events")
    outage_ids = [event["id"] for event in outage_events]
    expected_outage = fixture.expected_effects(outage_ids)
    down = fixture.snapshot(outage_ids)
    for event in outage_events:
        dispatch = down["dispatch"].get(event["id"])
        require(dispatch and dispatch["published_at"] == "" and dispatch["state"] in ("PENDING", "LEASED"),
                f"dispatch for {event['id']} was published while the broker was down: {dispatch}")
    require(not down["inbox"], "inbox markers exist while the broker is down")
    time.sleep(5)
    settled = fixture.snapshot(outage_ids)
    require(all(settled["dispatch"][event['id']]["published_at"] == "" for event in outage_events),
            "a dispatch row was published while the broker was down")
    stack.compose("start", "rabbitmq")
    broker_ready_at = stack.wait_rabbitmq_ready()
    broker_readiness_at = iso_now()
    outage_observation = fixture.wait_completed(outage_ids, expected_outage, RECOVERY_BOUND_SECONDS,
                                                broker_ready_at)
    outage_mailpit = fixture.verify_mailpit(outage_ids)
    broker_completed_at = iso_now()
    def topology_recovered():
        queue = stack.rabbitmq_request("queues/%2F/procureflow.notifications")
        if queue.get("durable") and (queue.get("consumer_details") or []):
            return queue
        return None

    topology = wait_for("durable queue and reconnected consumer after the broker restart",
                        topology_recovered, 60)
    cases.append({
        "case": "AC-OUTAGE/broker-restart",
        "commandStartedAt": broker_command_started_at,
        "commandCompletedAt": broker_command_completed_at,
        "brokerReadinessAt": broker_readiness_at,
        "completedAt": broker_completed_at,
        "completionObservedAt": outage_observation.completed_at,
        "elapsedFromBrokerStopSeconds": elapsed_iso(broker_stopped_at, broker_completed_at),
        "commandSeconds": round(command_seconds, 3),
        "events": [event["id"] for event in outage_events],
        "dispatchUnpublishedWhileDown": True,
        "observedRecoverySeconds": outage_observation.seconds,
        "recoveryBoundSeconds": RECOVERY_BOUND_SECONDS,
        "mailpit": outage_mailpit,
        "queueSurvivedRestart": {"durable": topology.get("durable"),
                                 "consumers": len(topology.get("consumer_details") or [])},
    })
    stack.log(f"PASS AC-OUTAGE broker: approval committed in {command_seconds:.2f}s with pending "
              f"outbox; recovery {outage_observation.seconds:.1f}s after broker readiness", flush=True)

    # ------------------------------------------------------------- AC-OUTAGE SMTP
    smtp_outage_at = iso_now()
    stack.compose("stop", "mailpit")
    smtp_request = fixture.create_and_approve(85, "department", "P4 smtp-outage fixture")[0]
    smtp_events = fixture.events_for([smtp_request])
    require(len(smtp_events) == 2, "smtp-outage fixture did not commit exactly two events")
    smtp_ids = [event["id"] for event in smtp_events]
    expected_smtp = fixture.expected_effects(smtp_ids)

    def smtp_backoff_ready():
        snapshot = fixture.snapshot(smtp_ids)
        jobs = snapshot["email_jobs"]
        if len(jobs) != sum(entry["email"] for entry in expected_smtp.values()):
            return None
        backed_off = [job for job in jobs if job["state"] == "PENDING" and int(job["attempts"]) >= 1
                      and job["error_code"] == "smtp_unreachable"]
        if len(backed_off) != len(jobs) or snapshot["inbox"].keys() != set(smtp_ids):
            return None
        return {"jobs": backed_off, "inApp": snapshot["in_app"]}

    backoff = wait_for("durable smtp_unreachable backoff", smtp_backoff_ready, 60)
    require(len(backoff["inApp"]) == sum(entry["in_app"] for entry in expected_smtp.values()),
            "in-app effects are not independent from the SMTP outage")
    smtp_snapshot = fixture.snapshot(smtp_ids)
    for username in ("b-employee", "b-manager"):
        identity = fixture.identity_id(username)
        visible = {row["event_id"] for row in smtp_snapshot["in_app"] if row["identity_id"] == identity}
        page_ids = {entry["eventId"] for entry in fixture.notification_page(username)["items"]}
        require(visible and visible <= page_ids,
                f"the live API list does not expose {username} materialized in-app effects during the SMTP outage")
    stack.compose("start", "mailpit")
    mailpit_ready_at = stack.wait_mailpit_ready()
    smtp_readiness_at = iso_now()
    smtp_observation = fixture.wait_completed(smtp_ids, expected_smtp, RECOVERY_BOUND_SECONDS,
                                              mailpit_ready_at)
    smtp_mailpit = fixture.verify_mailpit(smtp_ids)
    smtp_completed_at = iso_now()
    cases.append({
        "case": "AC-OUTAGE/mailpit-restart",
        "outageStartedAt": smtp_outage_at, "mailpitReadinessAt": smtp_readiness_at,
        "completedAt": smtp_completed_at,
        "completionObservedAt": smtp_observation.completed_at,
        "elapsedFromOutageStartSeconds": elapsed_iso(smtp_outage_at, smtp_completed_at),
        "events": smtp_ids,
        "durableBackoff": [{"job": job["id"], "attempts": job["attempts"], "state": job["state"],
                            "errorCode": job["error_code"]} for job in backoff["jobs"]],
        "inAppIndependent": len(backoff["inApp"]),
        "observedRecoverySeconds": smtp_observation.seconds,
        "recoveryBoundSeconds": RECOVERY_BOUND_SECONDS,
        "mailpit": smtp_mailpit,
    })
    stack.log(f"PASS AC-OUTAGE smtp: durable smtp_unreachable backoff with independent in-app "
              f"effects; delivery completed {smtp_observation.seconds:.1f}s after Mailpit readiness",
              flush=True)

    # ------------------------------------------------------------------ POISON
    marker = "p4verification-malformed-" + uuid.uuid4().hex
    probe_body = f'{{"p4verification":"{marker}","truncated":'
    poison_published_at = iso_now()
    publish = stack.rabbitmq_request(
        "exchanges/%2F/procureflow.events/publish",
        {"properties": {"delivery_mode": 2}, "routing_key": "request.submitted",
         "payload": probe_body, "payload_encoding": "string"})
    require(publish and publish.get("routed") is True, "the malformed probe was not routed to the queue")
    fingerprint = raw_fingerprint(probe_body)

    def poison_quarantine():
        rows = stack.worker_sql(
            "SELECT payload_hash, coalesce(company_id::text, ''), coalesce(event_id::text, ''), "
            "channel, error_code, attempts FROM notification_diagnostic "
            f"WHERE payload_hash = '{fingerprint}'")
        return rows if rows else None

    rows = wait_for("durable raw-scoped quarantine", poison_quarantine,
                    POISON_QUARANTINE_TIMEOUT_SECONDS)
    poison_quarantined_at = iso_now()
    require(len(rows) == 1, "the malformed probe did not produce exactly one diagnostic row")
    _, poison_company, poison_event, poison_channel, poison_code, poison_attempts = rows[0]
    require(poison_company == "" and poison_event == "",
            "raw-scoped quarantine must not carry company/event scope")
    require(poison_channel == "IN_APP" and poison_code == "malformed_message" and poison_attempts == "1",
            f"unexpected malformed quarantine row: {rows[0]}")
    diagnostics_for_event = stack.worker_sql(
        "SELECT count(*) FROM notification_diagnostic WHERE company_id IS NOT NULL")[0][0]
    require(int(diagnostics_for_event) == 0,
            "event-scoped diagnostics exist before the terminal-fault case")
    logs = stack.worker_logs(900)
    require(marker not in logs, "the malformed body marker leaked into worker logs")
    require(marker not in json.dumps(rows), "the malformed body marker leaked into the diagnostic table")
    followup_request = fixture.create_and_approve(95, "department", "P4 poison-followup fixture")[0]
    followup_events = fixture.events_for([followup_request])
    followup_ids = [event["id"] for event in followup_events]
    expected_followup = fixture.expected_effects(followup_ids)
    followup_observation = fixture.wait_completed(followup_ids, expected_followup,
                                                  RECOVERY_BOUND_SECONDS)
    followup_mailpit = fixture.verify_mailpit(followup_ids)
    cases.append({
        "case": "POISON/malformed-quarantine",
        "publishedAt": poison_published_at, "quarantinedAt": poison_quarantined_at,
        "validProgressCompletedAt": iso_now(),
        "validProgressObservedAt": followup_observation.completed_at,
        "payloadHash": fingerprint,
        "scope": {"company": None, "event": None, "channel": poison_channel, "errorCode": poison_code,
                  "attempts": int(poison_attempts)},
        "eventScopedDiagnosticsTotal": int(diagnostics_for_event),
        "bodyLeak": False,
        "validProgressEventIds": followup_ids,
        "validProgressSeconds": followup_observation.seconds,
        "mailpit": followup_mailpit,
    })
    stack.log(f"PASS POISON: malformed probe quarantined raw-scoped ({fingerprint[:12]}…) with no body "
              f"leak; valid events kept completing", flush=True)

    # ------------------------------------------------------ REPLAY with injected fault
    stack.stop_worker()
    fault_request = fixture.create_and_approve(90, "department", "P4 terminal-replay fixture")[0]
    fault_events = fixture.events_for([fault_request])
    require(len(fault_events) == 2, "terminal-replay fixture did not commit exactly two events")
    fault_ids = [event["id"] for event in fault_events]
    target = next(event for event in fault_events if event["event_type"] == "request.approved")
    target_id = target["id"]
    usage_before_fault = fixture.budget_usage()
    fault_started_at = iso_now()
    try:
        stack.install_materialization_fault(target_id)
        ready_at = stack.start_worker()
        wait_for("independent event completion during the fault", lambda: (
            target_id not in fixture.snapshot(fault_ids)["inbox"]
            and any(event_id in fixture.snapshot(fault_ids)["inbox"]
                    for event_id in fault_ids if event_id != target_id)), FAULT_TERMINAL_TIMEOUT_SECONDS)
        terminal = fixture.wait_terminal_diagnostic(target_id, FAULT_TERMINAL_TIMEOUT_SECONDS)
        terminal_at = iso_now()
        require(terminal[0][4] == "materialization_failed"
                and terminal[0][1] == fixture.company_id and terminal[0][2] == target_id,
                f"unexpected terminal diagnostic: {terminal}")
        quarantined = fixture.snapshot([target_id])
        require(not quarantined["inbox"] and not quarantined["deliveries"] and not quarantined["in_app"]
                and not quarantined["email_jobs"],
                "a terminal materialization failure left partial effects")
        require(quarantined["dispatch"][target_id]["state"] == "PUBLISHED",
                "the terminal event is not PUBLISHED after its poison acknowledgement")
        require(fixture.budget_usage() == usage_before_fault,
                "the terminal materialization failure changed the charged budget usage")

        def queue_idle():
            queue = stack.rabbitmq_request("queues/%2F/procureflow.notifications")
            if queue.get("messages_ready") == 0 and queue.get("messages_unacknowledged") == 0:
                return queue
            return None

        ack_queue = wait_for("queue drain after the terminal poison acknowledgement", queue_idle, 30)
        time.sleep(2)
        # The broker no longer holds the probe; re-reading proves the committed terminal
        # diagnostic was not lost by an acknowledgement that raced ahead of the commit.
        after_ack = fixture.wait_terminal_diagnostic(target_id, 5)
        require(after_ack[0][5] == terminal[0][5]
                and after_ack[0][1] == fixture.company_id and after_ack[0][2] == target_id,
                f"terminal diagnostic changed after its message was acknowledged: {after_ack}")
        require(not fixture.snapshot([target_id])["deliveries"]
                and not fixture.snapshot([target_id])["inbox"],
                "materialization effects appeared after the terminal poison acknowledgement")
    finally:
        stack.remove_materialization_fault()
    require(not stack.fault_present(), "the temporary materialization fault was not removed")

    stack.stop_worker()
    pre_replay = fixture.snapshot([target_id])
    require(pre_replay["dispatch"][target_id]["state"] == "PUBLISHED",
            "the replay event is not PUBLISHED before maintenance")
    replay_started_at = iso_now()
    stack.maintenance_replay(fixture.company_id, target_id)
    reset = fixture.snapshot([target_id])
    require(reset["dispatch"][target_id]["state"] == "PENDING"
            and reset["dispatch"][target_id]["attempts"] == "0"
            and reset["dispatch"][target_id]["published_at"] == "",
            "maintenance replay did not reset the scoped dispatch row")
    require(not stack.worker_sql("SELECT 1 FROM notification_diagnostic WHERE event_id = "
                                 f"'{sql_uuid(target_id)}'::uuid LIMIT 1"),
            "maintenance replay left event diagnostics in place")
    require(not reset["inbox"] and not reset["deliveries"] and not reset["in_app"]
            and not reset["email_jobs"],
            "maintenance replay mutated inbox/delivery/effect tables")
    require(stack.worker_sql(
        "SELECT count(*) FROM notification_diagnostic WHERE payload_hash = "
        f"'{fingerprint}'")[0][0] == "1",
        "the scoped maintenance replay cleared an unrelated raw malformed diagnostic")
    expected_fault = fixture.expected_effects(fault_ids)
    replay_restart_requested = time.monotonic()
    replay_restart_requested_at = iso_now()
    ready_at = stack.start_worker()
    replay_readiness_at = iso_now()
    replay_observation = fixture.wait_completed(fault_ids, expected_fault, RECOVERY_BOUND_SECONDS,
                                                ready_at)
    replay_mailpit = fixture.verify_mailpit(fault_ids)
    require(fixture.budget_usage() == usage_before_fault,
            "the maintenance replay changed the charged budget usage")
    replay_completed_at = iso_now()
    cases.append({
        "case": "REPLAY/terminal-fault-then-maintenance",
        "faultStartedAt": fault_started_at, "terminalAt": terminal_at,
        "maintenanceStartedAt": replay_started_at,
        "workerRestartRequestedAt": replay_restart_requested_at,
        "workerReadinessAt": replay_readiness_at,
        "completedAt": replay_completed_at,
        "completionObservedAt": replay_observation.completed_at,
        "elapsedFromFaultStartSeconds": elapsed_iso(fault_started_at, replay_completed_at),
        "workerRestartToReadinessSeconds": round(ready_at - replay_restart_requested, 3),
        "restartToCompletionSeconds": round(
            replay_observation.completed_at_monotonic - replay_restart_requested, 3),
        "eventId": target_id,
        "terminalDiagnostic": {"attempts": int(terminal[0][5]), "errorCode": terminal[0][4],
                               "companyScoped": True},
        "acknowledgedQueue": {"ready": ack_queue.get("messages_ready"),
                              "unacknowledged": ack_queue.get("messages_unacknowledged")},
        "terminalDurableAfterAck": True,
        "effectsDuringFault": False,
        "maintenanceOnlyDispatchAndDiagnostic": True,
        "maintenanceProcedure": MAINTENANCE_REPLAY_SQL_PATH.name,
        "maintenanceSqlSha256": hashlib.sha256(MAINTENANCE_REPLAY_SQL.encode("utf-8")).hexdigest(),
        "faultRemoved": True,
        "observedRecoverySeconds": replay_observation.seconds,
        "recoveryBoundSeconds": RECOVERY_BOUND_SECONDS,
        "mailpit": replay_mailpit,
    })
    stack.log(f"PASS REPLAY: terminal diagnostic after the bounded fault, then scoped maintenance "
              f"replay completed in {replay_observation.seconds:.1f}s", flush=True)

    # ------------------------------------------------------------- REVOCATION skip
    stack.stop_worker()
    revocation_stopped_at = iso_now()
    revocation_request = fixture.create_and_approve(60, "department", "P4 revocation fixture")[0]
    revocation_events = fixture.events_for([revocation_request])
    require(len(revocation_events) == 2, "revocation fixture did not commit exactly two events")
    manager_membership = fixture.membership("b-manager")
    status, _ = api_json(stack, tokens["b-admin"]["access_token"],
                         f"/api/admin/memberships/{manager_membership['id']}",
                         {"departmentId": manager_membership["departmentId"], "active": False,
                          "version": manager_membership["version"],
                          "roles": manager_membership.get("roles") or [],
                          "managedDepartmentIds": manager_membership.get("managedDepartmentIds") or []},
                         "PUT")
    require(status == 200, f"real admin revocation failed ({status})")
    revocation_applied_at = iso_now()
    revocation_ids = [event["id"] for event in revocation_events]
    expected_revocation = fixture.expected_effects(revocation_ids)
    manager_identity = manager_membership["identityId"]
    manager_mail_before = len(fixture.messages_to_identities([manager_identity]))
    ready_at = stack.start_worker()
    revocation_readiness_at = iso_now()
    revocation_observation = fixture.wait_completed(revocation_ids, expected_revocation,
                                                    RECOVERY_BOUND_SECONDS, ready_at)
    revoked_event = next(event for event in revocation_events if event["event_type"] == "request.submitted")
    revoked_snapshot = fixture.snapshot([revoked_event["id"]])
    skipped = [row for row in revoked_snapshot["deliveries"] if row["outcome"] == "SKIPPED"]
    require(len(skipped) == 2 and {row["reason_code"] for row in skipped} == {"membership_inactive"},
            f"revocation did not produce two membership_inactive skips: {skipped}")
    require(not revoked_snapshot["in_app"] and not revoked_snapshot["email_jobs"],
            "revoked recipient still received in-app/email effects")
    revocation_mailpit = fixture.verify_mailpit([event["id"] for event in revocation_events
                                                 if event["id"] != revoked_event["id"]])
    require(revocation_mailpit.get("capturedMessages", 0) == 1,
            "mail correlation after revocation failed")
    require(len(fixture.messages_to_identities([manager_identity])) == manager_mail_before,
            "a revoked recipient address received new mail")
    revocation_completed_at = iso_now()
    cases.append({
        "case": "REVOCATION/recipient-skip",
        "workerStoppedAt": revocation_stopped_at, "revocationAppliedAt": revocation_applied_at,
        "workerReadinessAt": revocation_readiness_at, "completedAt": revocation_completed_at,
        "completionObservedAt": revocation_observation.completed_at,
        "elapsedFromWorkerStopSeconds": elapsed_iso(revocation_stopped_at, revocation_completed_at),
        "eventId": revoked_event["id"],
        "skippedDeliveries": 2, "skipReason": "membership_inactive",
        "noInApp": True, "noEmailJob": True, "noMail": True,
        "observedRecoverySeconds": revocation_observation.seconds,
        "mailpit": revocation_mailpit,
    })
    stack.log("PASS REVOCATION: captured inactive recipient skipped on both channels without SMTP",
              flush=True)

    # ------------------------------------------------------------------ final sums
    require(len(fixture.request_ids) == FIXTURE_PLAN["requests"],
            f"fixture produced {len(fixture.request_ids)} requests, expected {FIXTURE_PLAN['requests']}")
    require(len(fixture.event_ids) == FIXTURE_PLAN["events"],
            f"fixture produced {len(fixture.event_ids)} events, expected {FIXTURE_PLAN['events']}")
    final = fixture.snapshot(fixture.event_ids)
    remaining = fixture.final_consistency_problems(final, fixture.event_ids)
    require(not remaining, "final effect sums are incomplete: " + "; ".join(remaining[:8]))
    final_in_app_by_identity = {}
    for row in final["in_app"]:
        final_in_app_by_identity.setdefault(row["identity_id"], set()).add(row["event_id"])
    role_lists = {}
    for username in ("b-employee", "b-finance", "b-admin"):
        identity = fixture.identity_id(username)
        page = fixture.notification_page(username)
        page_events = {entry["eventId"] for entry in page["items"]}
        expected_events = final_in_app_by_identity.get(identity, set())
        require(page_events == expected_events,
                f"final own-list read for {username} does not match its DB in-app rows: "
                f"{sorted(page_events)} vs {sorted(expected_events)}")
        role_lists[username] = {"expected": len(expected_events), "listed": len(page_events)}
    foreign_page = fixture.notification_page(FOREIGN_USER)
    require(not any(entry["eventId"] in fixture.event_ids for entry in foreign_page["items"]),
            "a foreign company's final list exposes fixture notifications")
    status, _ = api_json(stack, tokens["b-manager"]["access_token"], "/api/notifications?size=100")
    require(status == 403, f"the revoked manager's own-list read is not forbidden ({status})")
    final_mailpit = fixture.verify_all_captured_addresses()
    final_verified_at = iso_now()
    totals = {
        "requests": len(fixture.request_ids),
        "events": len(fixture.event_ids),
        "deliveryRows": len(final["deliveries"]),
        "inbox": len(final["inbox"]),
        "deliveriesMaterialized": len([row for row in final["deliveries"]
                                       if row["outcome"] == "MATERIALIZED"]),
        "deliveriesSkipped": len([row for row in final["deliveries"] if row["outcome"] == "SKIPPED"]),
        "inApp": len(final["in_app"]),
        "emailJobsSent": len([job for job in final["email_jobs"] if job["state"] == "SENT"]),
        "emailJobsNotSent": len([job for job in final["email_jobs"] if job["state"] != "SENT"]),
        "dispatchPublished": len([state for state in final["dispatch"].values()
                                  if state["state"] == "PUBLISHED"]),
        "dispatchNotPublished": len([state for state in final["dispatch"].values()
                                     if state["state"] != "PUBLISHED"]),
    }
    require(totals["deliveryRows"] == FIXTURE_PLAN["deliveryRows"]
            and totals["deliveriesMaterialized"] == FIXTURE_PLAN["materializedDeliveries"]
            and totals["deliveriesSkipped"] == FIXTURE_PLAN["skippedDeliveries"],
            f"fixture delivery sums drifted from the declared plan: {totals} vs {FIXTURE_PLAN}")

    def queue_drained():
        queue = stack.rabbitmq_request("queues/%2F/procureflow.notifications")
        if queue.get("messages_ready") == 0 and queue.get("messages_unacknowledged") == 0:
            return queue
        return None

    queue = wait_for("notification queue drain", queue_drained, QUEUE_DRAIN_TIMEOUT_SECONDS)
    status, backlog = api_json(stack, tokens["b-admin"]["access_token"],
                               "/api/admin/notifications/backlog")
    require(status == 200 and backlog, f"backlog read failed ({status})")
    require(backlog["inboxTotal"] == totals["events"], f"backlog inbox mismatch: {backlog}")
    require(backlog["dispatch"] == {"pending": 0, "leased": 0, "published": totals["events"],
                                    "quarantined": 0}, f"backlog dispatch mismatch: {backlog['dispatch']}")
    require(backlog["email"]["sent"] == totals["emailJobsSent"]
            and backlog["email"]["pending"] == 0 and backlog["email"]["leased"] == 0
            and backlog["email"]["quarantined"] == 0 and backlog["email"]["skipped"] == 0,
            f"backlog email mismatch: {backlog['email']}")
    require(backlog["deliveries"]["materialized"] == totals["deliveriesMaterialized"]
            and backlog["deliveries"]["skipped"] == totals["deliveriesSkipped"],
            f"backlog delivery mismatch: {backlog['deliveries']}")
    poison_rows = stack.worker_sql("SELECT count(*) FROM notification_diagnostic WHERE company_id IS NULL")
    require(poison_rows[0][0] == "1", f"expected exactly one raw-scoped quarantine row: {poison_rows}")
    verified_mail_total = sum(len(case.get("mailpit", {}).get("verified", [])) for case in cases)
    require(verified_mail_total == totals["emailJobsSent"],
            f"Mailpit correlation covered {verified_mail_total} of {totals['emailJobsSent']} sent jobs")
    cases.append({
        "case": "FINAL/sums-and-queue",
        "verifiedAt": final_verified_at,
        "totals": totals,
        "mailpitVerifiedAcrossCases": verified_mail_total,
        "mailpitRestartClearedEarlierCaptures": True,
        "roleLists": role_lists,
        "foreignListScoped": True,
        "revokedManagerListStatus": 403,
        "mailpitGlobal": final_mailpit,
        "queue": {"ready": queue.get("messages_ready"), "unacknowledged": queue.get("messages_unacknowledged"),
                  "consumers": len(queue.get("consumer_details") or [])},
        "adminBacklog": backlog,
        "rawScopedDiagnostics": int(poison_rows[0][0]),
    })
    stack.log(f"PASS final sums: {totals['events']} events, {totals['inApp']} in-app, "
              f"{totals['emailJobsSent']} sent emails, {totals['deliveriesSkipped']} skips; "
              f"queue drained", flush=True)

    return {
        "scope": {"company": "company B (Northstar) fixture data",
                  "owners": list(COMPANY_B_USERS), "foreignBoundaryUser": FOREIGN_USER,
                  "fixtureData": "disposable stack only; no product state reset beyond the two scoped replays"},
        "recoveryBoundSeconds": RECOVERY_BOUND_SECONDS,
        "fixturePlan": FIXTURE_PLAN,
        "budget": budget,
        "cases": cases,
        "procedure": {
            "maintenanceReplaySql": MAINTENANCE_REPLAY_SQL,
            "temporaryFaultSql": FAULT_SQL,
        },
        "limitations": [
            "The raw-scoped poison fingerprint cannot reconstruct the malformed message for replay.",
            "The broker keeps the malformed probe until the consumer acknowledges it; only broker-side "
            "storage exists during that window.",
            "The SMTP acceptance-before-save duplicate window is covered by the real-service worker tests, "
            "not injected by this smoke.",
            "Mailpit stores captures in an ephemeral tmpfs; the AC-OUTAGE restart intentionally clears "
            "earlier captures after each case has already been correlated, so the final global scan only "
            "covers messages that survived the last restart.",
            "The 60-second recovery value is a fixture-defined bound for a small local fixture, not an SLA.",
            "Email capture is Mailpit-only; no real or external address is ever used.",
        ],
    }


def _rabbitmq_unreachable(stack):
    try:
        stack.rabbitmq_request("overview")
        return False
    except (TransientWaitError, subprocess.CalledProcessError, urllib.error.URLError, OSError):
        return True
