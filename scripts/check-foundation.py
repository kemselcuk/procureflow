#!/usr/bin/env python3
"""Build/check a disposable local Compose stack, then delete only its own volumes."""

import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import urllib.request
import uuid
from oidc_smoke import check_identity_journey


ROOT = Path(__file__).resolve().parents[1]


def main():
    project = f"procureflow-check-{uuid.uuid4().hex[:12]}"
    environment = {
        **os.environ,
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "APP_DB_PASSWORD": secrets.token_hex(24),
        "MIGRATION_DB_PASSWORD": secrets.token_hex(24),
        "WORKER_DB_PASSWORD": secrets.token_hex(24),
        "KEYCLOAK_DB_PASSWORD": secrets.token_hex(24),
        "KEYCLOAK_ADMIN_PASSWORD": secrets.token_hex(24),
        "DEMO_PASSWORD": secrets.token_hex(24),
        "COMPOSE_PROJECT_NAME": project,
        "POSTGRES_PORT": "0",
        "APP_PORT": "0",
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

    def base_url(service):
        result = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "port", service, "8080"],
            cwd=ROOT, env=environment, check=True, capture_output=True, text=True,
        )
        address = result.stdout.strip()
        if not address.startswith("127.0.0.1:") or not address.split(":")[-1].isdigit():
            raise RuntimeError(f"Unexpected loopback port mapping for {service}")
        return "http://" + address

    compose("config", "--quiet")
    results = []
    try:
        compose("build")
        compose("up", "--wait", "--wait-timeout", "240")
        app = base_url("app")
        frontend = base_url("frontend")
        checks = [
            (app, "/actuator/health/readiness"),
            (frontend, "/api/system"),
            (frontend, "/v3/api-docs"),
            (frontend, "/"),
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
                else:
                    valid = json.loads(body)["status"] == "UP"
                if not valid:
                    raise RuntimeError(f"Unexpected foundation response for {path}")
                results.append({"url": base + path, "status": response.status})
                print(f"PASS {base}{path}", flush=True)
        for service in ("app", "notification-worker"):
            compose("exec", "-T", service, "java", "-version")
        compose("exec", "-T", "frontend", "nginx", "-t")
        provider = "http://localhost:" + environment["KEYCLOAK_PORT"] + "/realms/procureflow"
        oidc = check_identity_journey(provider, "http://localhost:" + environment["FRONTEND_PORT"], environment["DEMO_PASSWORD"])
    except Exception:
        diagnostics = subprocess.run(
            ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "--profile", "worker", "logs", "--no-color", "--tail", "100"],
            cwd=ROOT, env=environment, capture_output=True, text=True,
        ).stdout
        for name, value in environment.items():
            if name.endswith("PASSWORD") and value:
                diagnostics = diagnostics.replace(value, "[REDACTED]")
        diagnostics = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED_TOKEN]", diagnostics)
        output = ROOT / "target" / (project + "-failure.log")
        output.parent.mkdir(exist_ok=True)
        output.write_text(diagnostics)
        print(diagnostics[-10000:], flush=True)
        print(f"Redacted failure diagnostics: {output}", flush=True)
        raise
    finally:
        # The generated project name cannot target the user's persistent development stack.
        compose("down", "--volumes", "--remove-orphans")

    output = ROOT / "target" / "foundation-smoke.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"project": project, "checks": results, "healthyServices": 5, "oidc": oidc}, indent=2) + "\n")
    print(f"PASS isolated foundation stack and cleanup. Report: {output}")


if __name__ == "__main__":
    main()
