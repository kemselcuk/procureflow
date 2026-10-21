#!/usr/bin/env python3
"""Build/check a disposable local Compose stack, then delete only its own volumes."""

import base64
import http.client
import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from notification_smoke import (Fixture, NotificationStack, TransientWaitError, api_json,
                                check_notification_pipeline, login, uuid_in, wait_for)
from oidc_smoke import check_identity_journey


ROOT = Path(__file__).resolve().parents[1]

# Exact pinned image reference; the smoke check fails if Compose resolves anything else.
MAILPIT_IMAGE = (
    "axllent/mailpit:v1.31.6"
    "@sha256:23fe39e81bde596a918000b3135c0f85dc8bb4c9ea6be274b051143f3e300387"
)
MAILPIT_DIGEST = MAILPIT_IMAGE.split("@", 1)[1]
MAILPIT_REPO_DIGEST = "axllent/mailpit@" + MAILPIT_DIGEST

SECRET_NAME_PATTERN = re.compile(r"(?i)(PASSWORD|SECRET|TOKEN)")
SECRET_ASSIGNMENT_PATTERN = re.compile(r"(?i)(PGPASSWORD=)([^\s'\"]+)")
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
BASIC_AUTH_PATTERN = re.compile(r"(?i)\bBasic\s+[A-Za-z0-9+/=]+")


def secret_values(environment):
    """Only values, never their names, for replacing generated secrets in captured text."""
    return [value for name, value in environment.items()
            if value and SECRET_NAME_PATTERN.search(name) and len(value) >= 6]


def redact_text(text, values=()):
    """Fixed sanitizer for diagnostics; never keeps a config dump or an arbitrary body."""
    redacted = str(text)
    for value in values:
        if value:
            redacted = redacted.replace(value, "[REDACTED]")
    redacted = JWT_PATTERN.sub("[REDACTED_TOKEN]", redacted)
    redacted = BASIC_AUTH_PATTERN.sub("Basic [REDACTED]", redacted)
    redacted = SECRET_ASSIGNMENT_PATTERN.sub(r"\1[REDACTED]", redacted)
    return redacted


def sanitized_smtp_fields(worker_environment):
    """The two non-secret fields used in a wiring error; never the whole worker environment."""
    return {
        "SMTP_HOST": str(worker_environment.get("SMTP_HOST", "<unset>")),
        "SMTP_PORT": str(worker_environment.get("SMTP_PORT", "<unset>")),
    }


def main():
    project = f"procureflow-check-{uuid.uuid4().hex[:12]}"
    environment = {
        **os.environ,
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "APP_DB_PASSWORD": secrets.token_hex(24),
        "MIGRATION_DB_PASSWORD": secrets.token_hex(24),
        "WORKER_DB_PASSWORD": secrets.token_hex(24),
        "RABBITMQ_PASSWORD": secrets.token_hex(24),
        "KEYCLOAK_DB_PASSWORD": secrets.token_hex(24),
        "KEYCLOAK_ADMIN_PASSWORD": secrets.token_hex(24),
        "DEMO_PASSWORD": secrets.token_hex(24),
        "COMPOSE_PROJECT_NAME": project,
        # 0 lets Docker assign random loopback host ports for this disposable project.
        "POSTGRES_PORT": "0",
        "APP_PORT": "0",
        "WORKER_PORT": "0",
        "RABBITMQ_AMQP_PORT": "0",
        "RABBITMQ_MANAGEMENT_PORT": "0",
        "MAILPIT_SMTP_PORT": "0",
        "MAILPIT_UI_PORT": "0",
    }
    # Stable URLs are needed before Keycloak imports its exact redirect/issuer configuration.
    with socket.socket() as frontend_port, socket.socket() as oidc_port:
        frontend_port.bind(("127.0.0.1", 0))
        oidc_port.bind(("127.0.0.1", 0))
        environment["FRONTEND_PORT"] = str(frontend_port.getsockname()[1])
        environment["KEYCLOAK_PORT"] = str(oidc_port.getsockname()[1])

    def compose(*arguments):
        return subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker", *arguments],
            cwd=ROOT, env=environment, check=True,
        )

    # The one-shot migrate service is a dependency of app. `docker compose up --wait` fails when
    # an exited one-shot container is itself a wait target, so the seven long-running services are
    # named explicitly; migrate is still awaited through app's service_completed_successfully
    # condition and is verified separately for its exit code.
    WAIT_TARGETS = ("postgres", "app", "frontend", "keycloak", "notification-worker",
                    "rabbitmq", "mailpit")

    def compose_up_wait():
        compose("up", "--wait", "--wait-timeout", "240", *WAIT_TARGETS)

    def service_address(service, container_port):
        def query_port():
            result = subprocess.run(
                ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "port", service, str(container_port)],
                cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
            )
            return result.stdout.strip()

        def query_publishers():
            result = subprocess.run(
                ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
                 "ps", "--format", "json", service],
                cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
            )
            for line in result.stdout.splitlines():
                if not line.strip():
                    continue
                entry = json.loads(line)
                for publisher in entry.get("Publishers") or []:
                    if publisher.get("TargetPort") == container_port and publisher.get("PublishedPort"):
                        return f"{publisher.get('URL') or '127.0.0.1'}:{publisher['PublishedPort']}"
            return ""

        # A busy daemon can transiently return an empty mapping right after `up --wait`;
        # retry briefly and fall back to the container's actual published binding.
        address = ""
        for _ in range(12):
            address = query_port() or query_publishers()
            if address.startswith("127.0.0.1:") and address.split(":")[-1].isdigit():
                return address
            time.sleep(1)
        raise RuntimeError(f"Unexpected loopback port mapping for {service}:{container_port}: {address!r}")

    def base_url(service, container_port=8080):
        return "http://" + service_address(service, container_port)

    def rabbitmq_request(path, body=None, method=None):
        credentials = base64.b64encode(
            f"procureflow:{environment['RABBITMQ_PASSWORD']}".encode()
        ).decode()
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            base_url("rabbitmq", 15672) + "/api/" + path, data=data,
            headers={"Authorization": "Basic " + credentials}, method=method,
        )
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                text = response.read().decode()
        except urllib.error.HTTPError as error:
            raise TransientWaitError(
                f"RabbitMQ management returned HTTP {error.code}") from None
        try:
            return json.loads(text) if text.strip() else None
        except ValueError:
            # Do not include the raw broker body in any error or traceback.
            raise TransientWaitError("RabbitMQ management returned a non-JSON body") from None

    def rabbitmq_api(path):
        return rabbitmq_request(path)

    def compose_config():
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
             "config", "--format", "json"],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        try:
            return json.loads(result.stdout)
        except ValueError:
            # Resolved Compose JSON contains every generated password; never let a JSON
            # error quote any part of it.
            raise RuntimeError("docker compose config did not return valid JSON") from None

    def container_id(service):
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "ps", "-q", service],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        identifier = result.stdout.strip()
        if not identifier:
            raise RuntimeError(f"No running container for {service}")
        return identifier

    def docker_inspect(reference, template):
        result = subprocess.run(
            ["docker", "inspect", "--format", template, reference],
            cwd=ROOT, check=True, capture_output=True, text=True,
        )
        return result.stdout.strip()

    def mailpit_api(path):
        request = urllib.request.Request(base_url("mailpit", 8025) + path)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                text = response.read().decode()
        except urllib.error.HTTPError as error:
            raise TransientWaitError(f"Mailpit API returned HTTP {error.code}") from None
        try:
            return json.loads(text)
        except ValueError:
            raise TransientWaitError("Mailpit API returned a non-JSON body") from None

    def check_mailpit(configuration):
        service = configuration["services"]["mailpit"]
        if service.get("image") != MAILPIT_IMAGE:
            raise RuntimeError(f"Mailpit image is not the pinned reference: {service.get('image')}")
        worker_service = configuration["services"]["notification-worker"]
        worker_environment = worker_service.get("environment") or {}
        observed_smtp = sanitized_smtp_fields(worker_environment)
        if observed_smtp != {"SMTP_HOST": "mailpit", "SMTP_PORT": "1025"}:
            # Never interpolate the full environment here: it contains database and broker
            # passwords, and an uncaught exception would replay them in the traceback.
            raise RuntimeError(
                "Worker SMTP environment is not wired to Mailpit: "
                f"SMTP_HOST={observed_smtp['SMTP_HOST']!r} "
                f"SMTP_PORT={observed_smtp['SMTP_PORT']!r}")
        if "mailpit" in (configuration["services"]["app"].get("depends_on") or {}):
            raise RuntimeError("The business application must not depend on Mailpit")

        info = mailpit_api("/api/v1/info")
        if info.get("Version") != "v1.31.6":
            raise RuntimeError(f"Unexpected Mailpit version: {info.get('Version')}")
        if info.get("Messages") != 0:
            raise RuntimeError(f"Disposable Mailpit must start empty: {info.get('Messages')}")

        smtp_host, smtp_port = service_address("mailpit", 1025).split(":")
        with socket.create_connection((smtp_host, int(smtp_port)), timeout=5) as connection:
            greeting = connection.recv(256)
        if not greeting.startswith(b"220"):
            raise RuntimeError(f"Unexpected Mailpit SMTP greeting: {greeting!r}")

        image_id = docker_inspect(container_id("mailpit"), "{{.Image}}")
        digests = json.loads(docker_inspect(image_id, "{{json .RepoDigests}}"))
        if MAILPIT_REPO_DIGEST not in digests:
            raise RuntimeError(f"Running Mailpit image digests do not contain the pin: {digests}")

        results.append({
            "url": base_url("mailpit", 8025) + "/api/v1/info",
            "status": 200,
            "mailpit": {
                "version": info["Version"],
                "image": MAILPIT_IMAGE,
                "digestVerified": True,
                "smtp": {"url": service_address("mailpit", 1025), "greeting": "220"},
                "storage": "ephemeral tmpfs SQLite, no named volume",
            },
        })
        print("PASS pinned Mailpit image, ephemeral capture and SMTP greeting", flush=True)

    def migrate_exit_state():
        """The one-shot migration service must have exited successfully, not just started."""
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
             "ps", "--all", "--format", "json", "migrate"],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        entries = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        if len(entries) != 1:
            raise RuntimeError(f"Expected exactly one migrate container, observed {len(entries)}")
        entry = entries[0]
        if entry.get("State") != "exited" or entry.get("ExitCode") != 0:
            raise RuntimeError(
                f"The migrate service did not exit successfully: state={entry.get('State')} "
                f"exit={entry.get('ExitCode')}")
        return {"state": entry.get("State"), "exitCode": entry.get("ExitCode")}

    def check_migrator_separation():
        """The long-lived application must not receive the migrator credential."""
        configuration = compose_config()
        app_service = configuration["services"]["app"]
        app_environment = app_service.get("environment") or {}
        if "MIGRATION_DB_PASSWORD" in app_environment:
            raise RuntimeError("The resolved app container still receives the migrator credential")
        if str(app_environment.get("SPRING_FLYWAY_ENABLED", "")).lower() != "false":
            raise RuntimeError("The application must run with Flyway disabled")
        migrate_environment = configuration["services"]["migrate"].get("environment") or {}
        if not migrate_environment.get("MIGRATION_DB_PASSWORD"):
            raise RuntimeError("The migrate service does not own the migrator credential")
        dependency = (app_service.get("depends_on") or {}).get("migrate") or {}
        if dependency.get("condition") != "service_completed_successfully":
            raise RuntimeError("The app must depend on migrate: service_completed_successfully")
        running_environment = json.loads(docker_inspect(container_id("app"), "{{json .Config.Env}}"))
        if any(entry.startswith("MIGRATION_DB_PASSWORD=") for entry in running_environment):
            raise RuntimeError("The running app container still receives the migrator credential")
        state = migrate_exit_state()
        history = notification_stack.owner_sql(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
        if not history or history[0][0] != "5":
            raise RuntimeError(f"The migrated schema is not at the expected version: {history}")
        print("PASS migrator separation: migrate exited 0, app has no migrator credential and Flyway is disabled",
              flush=True)
        return {"migrate": state, "schemaVersion": history[0][0], "appHasMigratorCredential": False,
                "appFlywayEnabled": False}

    def check_security_headers():
        """Real HTTP evidence for the nginx browser-security headers and the built index."""
        request = urllib.request.Request(frontend + "/", headers={"Accept": "text/html"})
        with urllib.request.urlopen(request, timeout=20) as response:
            headers = {name.lower(): value for name, value in response.headers.items()}
            body = response.read().decode()
        csp = headers.get("content-security-policy", "")

        def csp_directives(header):
            directives = {}
            for part in header.split(";"):
                tokens = part.split()
                if tokens:
                    directives[tokens[0].lower()] = [token.lower() for token in tokens[1:]]
            return directives

        directives = csp_directives(csp)
        script_src = directives.get("script-src", directives.get("default-src", []))
        weak_script_tokens = {"'unsafe-inline'", "'unsafe-eval'", "data:", "blob:", "*"}
        required = {
            "scriptSrcSelf": "script-src 'self'" in csp,
            # A substring match alone would accept a later 'unsafe-inline'/'unsafe-eval' addition;
            # the parsed directive must be exactly 'self' and must contain no weak token.
            "scriptSrcSelfOnly": script_src == ["'self'"],
            "scriptSrcNoWeakTokens": not (weak_script_tokens & set(script_src)),
            "styleSrcUnsafeInline": "style-src 'self' 'unsafe-inline'" in csp,
            "connectSrcSelf": "connect-src 'self'" in csp,
            "objectSrcNone": "object-src 'none'" in csp,
            "baseUriSelf": "base-uri 'self'" in csp,
            "formActionSelf": "form-action 'self'" in csp,
            "frameAncestorsNone": "frame-ancestors 'none'" in csp,
            "xFrameOptionsDeny": headers.get("x-frame-options") == "DENY",
            "coopSameOrigin": headers.get("cross-origin-opener-policy") == "same-origin",
            "nosniff": headers.get("x-content-type-options") == "nosniff",
            "referrerPolicy": headers.get("referrer-policy") == "strict-origin-when-cross-origin",
            "noInlineScript": re.search(r"<script(?![^>]*\bsrc=)[^>]*>", body) is None,
            "noInlineStyle": "<style" not in body,
        }
        failed = sorted(name for name, passed in required.items() if not passed)
        if failed:
            raise RuntimeError(f"Browser security headers are incomplete: {failed}")
        print("PASS browser security headers on /: CSP, frame-ancestors, XFO, COOP and a clean index",
              flush=True)
        return {"url": frontend + "/", "checks": required}

    def raw_json_request(base, path, token, body, *, key=None, chunked=False):
        """Bounded raw JSON request; returns (status, body). Headers and payloads are never logged."""
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        if key is not None:
            headers["Idempotency-Key"] = key
        connection = http.client.HTTPConnection(base.removeprefix("http://"), timeout=30)
        try:
            connection.request("POST", path, body=iter([body]) if chunked else body, headers=headers)
            response = connection.getresponse()
            return response.status, response.read().decode(errors="replace")
        finally:
            connection.close()

    def check_json_body_bound():
        """Oversized JSON is refused with 413 before deserialization on both HTTP paths."""
        token = login(notification_stack, "a-employee")["access_token"]
        normal = json.dumps({"title": "P8 body bound normal payload",
                             "justification": "Fictional local-only body-bound acceptance fixture",
                             "amount": 10.50}).encode()
        invalid = json.dumps({"title": "", "justification": "", "amount": 0}).encode()
        oversized = json.dumps({"title": "P8 oversized", "justification": "x" * 20_000,
                                "amount": 10.50}).encode()
        chunked_body = json.dumps({"title": "P8 chunked oversized", "justification": "x" * 24_000,
                                   "amount": 10.50}).encode()
        # Just above the 64k nginx edge bound; the edge answers with its own body, not problem+json.
        beyond_nginx = json.dumps({"title": "P8 nginx bound", "justification": "x" * 66_000,
                                   "amount": 10.50}).encode()
        observations = {}
        for label, base in (("app", app), ("frontend", frontend)):
            status, _ = raw_json_request(base, "/api/requests", token, normal, key=str(uuid.uuid4()))
            if status != 201:
                raise RuntimeError(f"A documented normal JSON payload was not accepted on {label} ({status})")
            observations[label + "Normal"] = status
            status, payload = raw_json_request(base, "/api/requests", token, invalid, key=str(uuid.uuid4()))
            if status != 400:
                raise RuntimeError(f"A small invalid form must still be a 400 on {label}, not {status}")
            observations[label + "Invalid"] = status
            status, payload = raw_json_request(base, "/api/requests", token, oversized, key=str(uuid.uuid4()))
            if status != 413:
                raise RuntimeError(f"An oversized declared Content-Length was not rejected on {label} ({status})")
            if "payload_too_large" not in payload:
                raise RuntimeError(f"The application-side 413 body on {label} is missing the stable error code")
            observations[label + "OversizedDeclared"] = status
            status, _ = raw_json_request(base, "/api/requests", token, chunked_body, chunked=True)
            if status != 413:
                raise RuntimeError(f"An oversized chunked JSON body was not rejected on {label} ({status})")
            observations[label + "OversizedChunked"] = status
        status, payload = raw_json_request(frontend, "/api/requests", token, beyond_nginx)
        if status != 413:
            raise RuntimeError(f"The nginx client_max_body_size edge bound did not reject ({status})")
        if "payload_too_large" in payload:
            raise RuntimeError("A body above 64k reached the application instead of the nginx edge bound")
        observations["frontendBeyondNginx"] = status
        print(f"PASS JSON body bound: normal 201, invalid 400, oversized declared/chunked 413 ({observations})",
              flush=True)
        return {"urls": {"app": app + "/api/requests", "frontend": frontend + "/api/requests"},
                "observations": observations}

    def check_existing_volume_restart():
        """Restart the same populated stack: migrate re-runs, exits 0 and data survives."""
        def business_state():
            rows = notification_stack.owner_sql(
                "SELECT (SELECT count(*) FROM purchase_request), "
                "(SELECT count(*) FROM purchase_request WHERE status = 'APPROVED'), "
                "(SELECT count(*) FROM audit_entry)")
            return tuple(int(value) for value in rows[0])

        before = business_state()
        if before[0] < 1 or before[1] < 1 or before[2] < 1:
            raise RuntimeError(f"The populated volume has no business data to preserve: {before}")
        compose("stop")
        compose_up_wait()
        after = business_state()
        if before != after:
            raise RuntimeError(f"Business data changed across the volume-preserving restart: {before} -> {after}")
        state = migrate_exit_state()
        print(f"PASS existing-volume restart: migrate exited 0 again and {before} stayed unchanged",
              flush=True)
        return {"before": list(before), "after": list(after), "migrate": state}

    def check_scheduler_isolation():
        """A blocked SMTP runner must not delay outbox publication (independent scheduler slots)."""
        tokens = {name: login(notification_stack, name)
                  for name in ("b-employee", "b-manager", "b-finance", "b-admin")}
        fixture = Fixture(notification_stack, tokens)
        # The notification pipeline deactivates the b-manager membership in its revocation case;
        # this isolation fixture needs an active department manager again. The single inactive
        # company membership is reactivated through the same real admin API as the revocation.
        status, memberships = api_json(notification_stack, tokens["b-admin"]["access_token"],
                                       "/api/admin/memberships")
        if status != 200 or not memberships:
            raise RuntimeError(f"Reading the isolation fixture memberships failed ({status})")
        inactive = [entry for entry in memberships if entry.get("active") is False]
        if len(inactive) > 1:
            raise RuntimeError(f"Expected at most one revoked fixture membership, observed {len(inactive)}")
        if inactive:
            record = inactive[0]
            status, _ = api_json(notification_stack, tokens["b-admin"]["access_token"],
                                 f"/api/admin/memberships/{record['id']}",
                                 {"departmentId": record["departmentId"], "active": True,
                                  "version": record["version"],
                                  "roles": record.get("roles") or [],
                                  "managedDepartmentIds": record.get("managedDepartmentIds") or []},
                                 "PUT")
            if status != 200:
                raise RuntimeError(f"Reactivating the isolation fixture membership failed ({status})")
        fixture.set_company_budgets()
        mailpit_container = container_id("mailpit")
        subprocess.run(["docker", "pause", mailpit_container], cwd=ROOT, check=True, capture_output=True)
        try:
            # A paused container keeps its TCP listener in the kernel while its process is
            # frozen: SMTP connects and then waits for the read timeout, exactly the blocked
            # I/O that must not occupy the relay's scheduler slot.
            batch = [fixture.create_and_approve(10, "department", f"P8 scheduler isolation {index}")[0]
                     for index in range(3)]
            batch_events = fixture.events_for(batch)
            if len(batch_events) < 6:
                raise RuntimeError(f"The isolation fixture committed {len(batch_events)} events, expected at least 6")
            batch_event_ids = uuid_in([event["id"] for event in batch_events])

            def blocked_sends():
                rows = notification_stack.worker_sql(
                    "SELECT count(*) FROM email_job WHERE attempts >= 1 "
                    f"AND event_id IN ({batch_event_ids})")
                return int(rows[0][0]) >= 2

            wait_for("two SMTP send attempts against the paused capture", blocked_sends, 60)

            def in_flight_smtp_connections():
                """ESTABLISHED sockets to the Mailpit SMTP port (IPv4/IPv6) in the worker's netns."""
                result = subprocess.run(
                    ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
                     "exec", "-T", "notification-worker", "cat", "/proc/net/tcp", "/proc/net/tcp6"],
                    cwd=ROOT, env=environment, check=True, capture_output=True, text=True)
                established = 0
                for line in result.stdout.splitlines():
                    fields = line.split()
                    if len(fields) < 4 or fields[3] != "01":
                        continue
                    if fields[2].rsplit(":", 1)[-1].upper() == "0401":  # 1025
                        established += 1
                return established

            wait_for("an in-flight SMTP connection to the paused capture",
                     lambda: in_flight_smtp_connections() >= 1, 30)
            in_flight = in_flight_smtp_connections()

            probe_request, _ = fixture.create_and_approve(10, "department", "P8 scheduler isolation probe")
            probe_events = fixture.events_for([probe_request])
            if len(probe_events) < 2:
                raise RuntimeError(f"The probe committed {len(probe_events)} events, expected at least 2")
            probe = next(event for event in probe_events if event["event_type"] == "request.approved")

            started = time.monotonic()

            def relay_published():
                rows = notification_stack.worker_sql(
                    "SELECT state FROM outbox_dispatch WHERE event_id IN (" + uuid_in([probe["id"]]) + ")")
                return bool(rows) and rows[0][0] == "PUBLISHED"

            wait_for("outbox publication while the SMTP runner is blocked", relay_published, 25)
            published_seconds = round(time.monotonic() - started, 3)
        finally:
            subprocess.run(["docker", "unpause", mailpit_container], cwd=ROOT, check=True, capture_output=True)

        # A paused container cannot run its healthcheck; after unpause the probe must recover
        # before the fixture completion and final-state assertions. Bounded recovery only: the
        # container is never recreated and no global/project-wide action is taken. The API URL is
        # resolved fresh because the random loopback port is re-assigned across a stack restart.
        def mailpit_recovered():
            try:
                if docker_inspect(mailpit_container, "{{.State.Health.Status}}") != "healthy":
                    return None
                info = mailpit_api("/api/v1/info")
                return bool(info) and info.get("Version") == "v1.31.6"
            except (urllib.error.URLError, OSError, ValueError):
                return None

        wait_for("the unpaused Mailpit container health and API to recover", mailpit_recovered, 60)

        all_events = [*batch_events, *probe_events]
        all_ids = [event["id"] for event in all_events]
        observation = fixture.wait_completed(all_ids, fixture.expected_effects(all_ids), 120)
        print(f"PASS scheduler isolation: {in_flight} in-flight SMTP connection(s) observed while the "
              f"capture was paused; relay published the probe event {published_seconds}s after commit; "
              f"the fixture completed in {observation.seconds:.1f}s and Mailpit recovered healthy", flush=True)
        return {"publishedWhileSmtpBlockedSeconds": published_seconds,
                "inFlightSmtpConnections": in_flight,
                "mailpitRecoveredHealthy": True,
                "fixtureEvents": len(all_ids), "completionSeconds": observation.seconds}

    def check_final_stack_state():
        """All seven long-running disposable services stay up and healthy; migrate still exited 0."""
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
             "ps", "--all", "--format", "json"],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        entries = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        states = {entry.get("Service"): entry for entry in entries}
        running = {service: entry for service, entry in states.items() if entry.get("State") == "running"}
        expected_services = {"postgres", "app", "frontend", "notification-worker", "rabbitmq"}
        expected_services |= {"keycloak", "mailpit"}
        if set(running) != expected_services:
            raise RuntimeError(f"Final running service set is not the seven long-running services: {sorted(running)}")
        for service, entry in sorted(running.items()):
            health = entry.get("Health") or ""
            if health not in ("", "healthy"):
                raise RuntimeError(f"Final health for {service} is not healthy: {health}")

        def final_http(label, base, path):
            last = "no attempt"
            # A container start may publish its loopback mapping a moment after the
            # container reports healthy; retry this readiness probe only, never a state
            # assertion, and surface the last sanitized failure if it never recovers.
            for _ in range(15):
                try:
                    with urllib.request.urlopen(base + path, timeout=5) as response:
                        if response.status == 200:
                            return
                        last = f"HTTP {response.status}"
                except (urllib.error.URLError, OSError, ValueError) as error:
                    last = type(error).__name__
                time.sleep(1)
            raise RuntimeError(f"Final HTTP readiness failed for {label}{path}: {last}")

        for label, base, path in (
                ("app", base_url("app"), "/actuator/health/readiness"),
                ("worker", base_url("notification-worker", 8081), "/actuator/health/readiness"),
                ("frontend", base_url("frontend"), "/api/system"),
                ("mailpit", base_url("mailpit", 8025), "/readyz")):
            final_http(label, base, path)
        image_id = docker_inspect(container_id("mailpit"), "{{.Image}}")
        digests = json.loads(docker_inspect(image_id, "{{json .RepoDigests}}"))
        if MAILPIT_REPO_DIGEST not in digests:
            raise RuntimeError(f"Final Mailpit image digests do not contain the pin: {digests}")
        migrate = migrate_exit_state()
        summary = {service: {"state": entry.get("State"), "health": entry.get("Health") or "none"}
                   for service, entry in sorted(running.items())}
        print(f"PASS final seven-service state after restart cases: {summary} "
              f"(migrate exited {migrate['exitCode']})", flush=True)
        return {"services": summary, "mailpitDigestPinned": True, "migrate": migrate}

    def verify_cleanup():
        checks = {}
        for kind, command in (("containers", ["docker", "ps", "-aq", "--filter",
                                              f"label=com.docker.compose.project={project}"]),
                              ("volumes", ["docker", "volume", "ls", "-q", "--filter",
                                           f"label=com.docker.compose.project={project}"]),
                              ("networks", ["docker", "network", "ls", "-q", "--filter",
                                            f"label=com.docker.compose.project={project}"])):
            result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
            leftovers = [line for line in result.stdout.splitlines() if line.strip()]
            if leftovers:
                raise RuntimeError(f"Cleanup left project {kind}: {leftovers}")
            checks[kind] = 0
        print("PASS owned cleanup: no project containers, volumes or networks remain", flush=True)
        return checks

    compose("config", "--quiet")
    results = []
    try:
        compose("build")
        compose_up_wait()
        app = base_url("app")
        frontend = base_url("frontend")
        worker = base_url("notification-worker", 8081)
        mailpit = base_url("mailpit", 8025)
        checks = [
            (app, "/actuator/health/readiness"),
            (worker, "/actuator/health/readiness"),
            (frontend, "/api/system"),
            (frontend, "/v3/api-docs"),
            (frontend, "/"),
            (mailpit, "/readyz"),
        ]
        for base, path in checks:
            with urllib.request.urlopen(base + path, timeout=20) as response:
                body = response.read().decode()
                if response.status != 200:
                    raise RuntimeError(f"Unexpected HTTP status for {path}: {response.status}")
                if path == "/api/system":
                    valid = json.loads(body) == {"name": "ProcureFlow", "stage": "foundation"}
                elif path == "/v3/api-docs":
                    valid = "/api/system" in json.loads(body)["paths"]
                elif path == "/":
                    valid = "<app-root>" in body
                elif path == "/readyz":
                    valid = body == ""
                else:
                    valid = json.loads(body)["status"] == "UP"
                if not valid:
                    raise RuntimeError(f"Unexpected foundation response for {path}")
                results.append({"url": base + path, "status": response.status})
                print(f"PASS {base}{path}", flush=True)

        exchange = rabbitmq_api("exchanges/%2F/procureflow.events")
        bindings = rabbitmq_api("bindings/%2F/e/procureflow.events/q/procureflow.notifications")

        def topology_ready():
            # The consumer is enabled by default; a bounded wait tolerates the broker stats
            # interval and listener startup instead of requiring an empty queue.
            queue = rabbitmq_api("queues/%2F/procureflow.notifications")
            consumers = queue.get("consumer_details") or []
            if (queue.get("durable") and not queue.get("auto_delete") and not queue.get("exclusive")
                    and queue.get("type") == "classic" and len(consumers) >= 1):
                return queue
            return None

        queue = wait_for("a connected durable consumer on procureflow.notifications",
                         topology_ready, 90)
        topology_valid = (
            exchange["durable"] and exchange["type"] == "topic" and not exchange["auto_delete"]
            and queue["durable"] and not queue["auto_delete"] and not queue["exclusive"]
            and any(binding["routing_key"] == "request.*" for binding in bindings)
        )
        if not topology_valid:
            raise RuntimeError(
                f"Unexpected durable topology: exchange={exchange} queue={queue} bindings={bindings}"
            )
        consumers = len(queue.get("consumer_details") or [])
        results.append({
            "url": base_url("rabbitmq", 15672) + "/api/queues/%2F/procureflow.notifications",
            "status": 200,
            "topology": {
                "exchange": "procureflow.events/topic/durable",
                "queue": "procureflow.notifications/classic/durable",
                "binding": "request.*",
                "consumers": consumers,
            },
        })
        print(f"PASS durable topic exchange, classic queue, request.* binding and {consumers} consumer(s)",
              flush=True)

        check_mailpit(compose_config())

        for service in ("app", "notification-worker"):
            compose("exec", "-T", service, "java", "-version")
        compose("exec", "-T", "frontend", "nginx", "-t")
        provider = "http://localhost:" + environment["KEYCLOAK_PORT"] + "/realms/procureflow"
        notification_stack = NotificationStack(
            root=ROOT,
            compose_argv=["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker"],
            environment=environment,
            project=project,
            frontend=frontend,
            provider=provider,
            service_address=service_address,
            base_url=base_url,
            rabbitmq_request=rabbitmq_request,
            mailpit_api=mailpit_api,
        )

        migrator = check_migrator_separation()
        results.append({"case": "migrator-separation", **migrator})

        security_headers = check_security_headers()
        results.append({"case": "security-headers", **security_headers})

        body_bound = check_json_body_bound()
        results.append({"case": "json-body-bound", **body_bound})

        oidc = check_identity_journey(provider, "http://localhost:" + environment["FRONTEND_PORT"], environment["DEMO_PASSWORD"])

        notification = check_notification_pipeline(notification_stack)
        results.append({"case": "notification-pipeline", "caseCount": len(notification["cases"])})

        restart = check_existing_volume_restart()
        results.append({"case": "existing-volume-restart", **restart})

        isolation = check_scheduler_isolation()
        results.append({"case": "scheduler-isolation", **isolation})

        final_state = check_final_stack_state()
    except Exception:
        diagnostics = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker", "logs", "--no-color", "--tail", "100"],
            cwd=ROOT, env=environment, capture_output=True, text=True,
        ).stdout
        values = secret_values(environment)
        diagnostics = redact_text(diagnostics, values)
        output = ROOT / "target" / (project + "-failure.log")
        output.parent.mkdir(exist_ok=True)
        output.write_text(diagnostics)
        print(redact_text(diagnostics[-10000:], values), flush=True)
        print(f"Redacted failure diagnostics: {output}", flush=True)
        raise
    finally:
        # The generated project name cannot target the user's persistent development stack.
        compose("down", "--volumes", "--remove-orphans")
        cleanup = verify_cleanup()

    output = ROOT / "target" / "foundation-smoke.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({
        "project": project,
        "checks": results,
        "healthyServices": 7,
        "migrateService": migrator["migrate"],
        "oidc": oidc,
        "notification": notification,
        "finalStackState": final_state,
        "cleanup": cleanup,
    }, indent=2) + "\n")
    print(f"PASS isolated foundation stack and cleanup. Report: {output}")


if __name__ == "__main__":
    main()
