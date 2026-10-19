#!/usr/bin/env python3
"""Build/check a disposable local Compose stack, then delete only its own volumes."""

import base64
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
from notification_smoke import (NotificationStack, TransientWaitError,
                                check_notification_pipeline, wait_for)
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

    def check_final_stack_state():
        """All seven disposable services stay up and healthy after the outage/restart cases."""
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker",
             "ps", "--format", "json"],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        entries = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        states = {entry.get("Service"): entry for entry in entries}
        expected_services = {"postgres", "app", "frontend", "notification-worker", "rabbitmq"}
        expected_services |= {"keycloak", "mailpit"}
        if set(states) != expected_services:
            raise RuntimeError(f"Final service set is not the seven disposable services: {sorted(states)}")
        for service, entry in sorted(states.items()):
            if entry.get("State") != "running":
                raise RuntimeError(f"Final state for {service} is not running: {entry.get('State')}")
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
        summary = {service: {"state": entry.get("State"), "health": entry.get("Health") or "none"}
                   for service, entry in sorted(states.items())}
        print(f"PASS final seven-service state after restart cases: {summary}", flush=True)
        return {"services": summary, "mailpitDigestPinned": True}

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
        compose("up", "--wait", "--wait-timeout", "240")
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
        oidc = check_identity_journey(provider, "http://localhost:" + environment["FRONTEND_PORT"], environment["DEMO_PASSWORD"])

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
        notification = check_notification_pipeline(notification_stack)
        results.append({"case": "notification-pipeline", "caseCount": len(notification["cases"])})

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
        "oidc": oidc,
        "notification": notification,
        "finalStackState": final_state,
        "cleanup": cleanup,
    }, indent=2) + "\n")
    print(f"PASS isolated foundation stack and cleanup. Report: {output}")


if __name__ == "__main__":
    main()
