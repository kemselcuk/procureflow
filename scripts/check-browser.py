#!/usr/bin/env python3
"""Run the committed critical Playwright suite on a disposable seven-service stack.

The runner is deliberately narrow: it validates the pinned E2E dependency, builds and starts
only its own unique Compose project (including the worker profile), waits for real readiness,
executes ``e2e`` against the actual product, and always tears down only that project's
containers, volumes and network. All diagnostics are redacted before printing or persisting;
the sanitized JSON report lives under the ignored ``target/p6-browser`` directory.

It does not replace the existing fault/recovery checker (P6.2) and makes no outage,
cross-browser, availability or hosted-CI claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable, Mapping

ROOT = Path(__file__).resolve().parents[1]
E2E = ROOT / "e2e"
REPORT_DIR = ROOT / "target" / "p6-browser"
CRITICAL_CONTRACT = E2E / "critical-tests.json"
PLAYWRIGHT_PACKAGE = "@playwright/test"
PLAYWRIGHT_VERSION = "1.60.0"
SERVICE_COMMANDS = ("--profile", "worker")
EXPECTED_SERVICES = {
    "postgres", "app", "frontend", "notification-worker", "rabbitmq", "keycloak", "mailpit",
}
# One-shot schema owner, verified separately: it must have exited 0 before the app serves.
MIGRATE_SERVICE = "migrate"
# `docker compose up --wait` fails when an exited one-shot container is itself a wait target,
# so the long-running services are named explicitly; migrate is awaited through app's
# service_completed_successfully condition instead.
RUNNING_SERVICE_TARGETS = (
    "postgres", "app", "frontend", "keycloak", "notification-worker", "rabbitmq", "mailpit",
)
SECRET_NAME_PATTERN = re.compile(r"(?i)(PASSWORD|SECRET|TOKEN)")
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
BASIC_AUTH_PATTERN = re.compile(r"(?i)\bBasic\s+[A-Za-z0-9+/=]+")
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
PGPASSWORD_PATTERN = re.compile(r"(?i)(PGPASSWORD=)([^\s'\"]+)")
SECRET_ASSIGNMENT_PATTERN = re.compile(
    r"(?i)\b(access_token|refresh_token|id_token|code|state|session_state|iss|code_verifier|client_secret|password)"
    r"=([^&#\s\"']+)"
)
SENSITIVE_JSON_KEYS = (
    r"access_token|refresh_token|id_token|code|state|session_state|client_secret|password"
    r"|authorization|proxy-authorization|cookie|set-cookie|set-cookie2"
)
JSON_SECRET_PATTERN = re.compile(
    rf'(?i)("(?:{SENSITIVE_JSON_KEYS})"\s*:\s*")((?:\\.|[^"\\])*)"'
)
# The same structured value can reach a log after JSON-in-JSON escaping: {\"Cookie\":\"value\"}.
ESCAPED_JSON_SECRET_PATTERN = re.compile(
    rf'(?i)(\\"(?:{SENSITIVE_JSON_KEYS})\\"\s*:\s*\\")(.*?)(\\")'
)
SENSITIVE_HEADER_NAMES = r"authorization|proxy-authorization|cookie|set-cookie|set-cookie2"
# Accept Playwright list markers, bullets and quoted header names before the colon.
AUTH_HEADER_PATTERN = re.compile(
    rf"(?im)^[\s*\-•>]*[\"']?(?P<name>{SENSITIVE_HEADER_NAMES})[\"']?\s*:\s*.*$"
)
SENSITIVE_STRUCTURED_KEYS = frozenset({
    "authorization", "proxy-authorization", "cookie", "set-cookie", "set-cookie2",
})
URL_PATTERN = re.compile(r"https?://[^\s\"'<>]+")


class VerificationError(RuntimeError):
    """A bounded verifier failure with a deliberately small, sanitized message."""


class VerificationTimeout(VerificationError):
    """A setup, test or teardown deadline elapsed."""


def secret_values(environment: Mapping[str, str]) -> list[str]:
    """Values only, never names, for replacing generated secrets in captured text."""
    return [
        value for name, value in environment.items()
        if value and SECRET_NAME_PATTERN.search(name) and len(value) >= 6
    ]


def _strip_url_query(match: re.Match[str]) -> str:
    raw = match.group(0)
    try:
        parts = urllib.parse.urlsplit(raw)
    except ValueError:
        return "[REDACTED_URL]"
    if not parts.query and not parts.fragment:
        return raw
    query = "[REDACTED_QUERY]" if parts.query else ""
    fragment = "[REDACTED_FRAGMENT]" if parts.fragment else ""
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, query, fragment))


def redact_text(text: str, values: Iterable[str] = ()) -> str:
    """Sanitize generated secrets, auth material and callback query/fragment values."""
    redacted = str(text)
    for value in values:
        if value:
            redacted = redacted.replace(value, "[REDACTED]")
    redacted = JWT_PATTERN.sub("[REDACTED_JWT]", redacted)
    redacted = BEARER_PATTERN.sub("Bearer [REDACTED]", redacted)
    redacted = BASIC_AUTH_PATTERN.sub("Basic [REDACTED]", redacted)
    redacted = PGPASSWORD_PATTERN.sub(r"\1[REDACTED]", redacted)
    redacted = JSON_SECRET_PATTERN.sub(r'\1[REDACTED]"', redacted)
    redacted = ESCAPED_JSON_SECRET_PATTERN.sub(r"\1[REDACTED]\3", redacted)
    redacted = SECRET_ASSIGNMENT_PATTERN.sub(r"\1=[REDACTED]", redacted)
    redacted = AUTH_HEADER_PATTERN.sub(lambda match: f"{match.group('name')}: [REDACTED]", redacted)
    redacted = URL_PATTERN.sub(_strip_url_query, redacted)
    return redacted


def deep_redact(value: Any, values: Iterable[str] = ()) -> Any:
    if isinstance(value, str):
        return redact_text(value, values)
    if isinstance(value, list):
        return [deep_redact(item, values) for item in value]
    if isinstance(value, dict):
        redacted: dict[str, Any] = {}
        for key, item in value.items():
            name = str(key)
            if name.casefold() in SENSITIVE_STRUCTURED_KEYS:
                redacted[name] = "[REDACTED]"
            else:
                redacted[name] = deep_redact(item, values)
        return redacted
    return value


class Deadline:
    """A monotonic deadline. Late observations fail closed rather than being accepted."""

    def __init__(self, seconds: float, *, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._deadline = clock() + seconds

    def remaining(self) -> float:
        return max(0.0, self._deadline - self._clock())

    def expired(self) -> bool:
        return self._clock() >= self._deadline

    def require(self, operation: str, minimum: float = 1.0) -> float:
        remaining = self.remaining()
        if remaining < minimum:
            raise VerificationTimeout(f"The deadline for {operation} elapsed")
        return remaining


def bounded_timeout(seconds: float, operation: str) -> float:
    if seconds <= 0:
        raise VerificationTimeout(f"The deadline for {operation} elapsed")
    return seconds


def _terminate_owned_tree(process: subprocess.Popen[str]) -> None:
    """Kill only the process group created for this command, then reap it.

    ``subprocess.run(timeout=...)`` kills just the direct child. Playwright leaves node
    workers and Chromium descendants behind, so every verifier command starts its own POSIX
    session and a timeout terminates that owned group. No global node/Chrome kill is used.

    The group must be signalled even when ``process.poll()`` already reports the leader as
    reaped: ``communicate`` can reap the leader while a descendant still holds the inherited
    pipes open, and skipping the group signal would leak that descendant. The group id is the
    direct child's pid because ``run_command`` always starts a new session; an already empty
    group is harmless (ESRCH). The caller's own group is never signalled.
    """
    if os.name == "posix":
        try:
            if process.pid > 0 and process.pid != os.getpgrp():
                os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            pass
    elif process.poll() is None:  # pragma: no cover - the verifier targets macOS/Linux
        try:
            process.kill()
        except OSError:
            pass
    try:
        process.communicate(timeout=5)
    except (subprocess.TimeoutExpired, OSError):
        try:
            process.kill()
        except OSError:
            pass
        try:
            process.wait(timeout=5)
        except (subprocess.TimeoutExpired, OSError):
            pass


def run_command(
    argv: list[str],
    *,
    cwd: Path,
    environment: Mapping[str, str],
    timeout: float,
    operation: str,
) -> subprocess.CompletedProcess[str]:
    """Run an owned command tree; a timeout kills its group so no browser can survive."""
    # Validate before Popen: a rejected timeout must not leave a started process behind.
    timeout = bounded_timeout(timeout, operation)
    try:
        process = subprocess.Popen(
            argv,
            cwd=cwd,
            env=dict(environment),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=os.name == "posix",
        )
    except OSError as error:
        raise VerificationError(f"{operation} could not start: {type(error).__name__}") from None
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _terminate_owned_tree(process)
        raise VerificationTimeout(f"The deadline for {operation} elapsed") from None
    except OSError as error:
        _terminate_owned_tree(process)
        raise VerificationError(f"{operation} failed to complete: {type(error).__name__}") from None
    return subprocess.CompletedProcess(argv, process.returncode, stdout or "", stderr or "")


def _require_success(result: subprocess.CompletedProcess[str], operation: str) -> None:
    if result.returncode != 0:
        # stderr is not embedded here; the verifier records a sanitized failure log centrally.
        raise VerificationError(f"{operation} failed with exit code {result.returncode}")


def _timestamp() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _read_version(path: Path) -> str | None:
    try:
        package = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return package.get("version") if isinstance(package, dict) else None


def _git_text(*arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments], cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise VerificationError("The tested Git revision could not be read")
    return result.stdout


def source_manifest() -> dict[str, Any]:
    """Return the tested revision plus a deterministic hash manifest of dirty/untracked files."""
    revision = _git_text("rev-parse", "HEAD").strip()
    changed = [item for item in _git_text("diff", "--name-only", "-z", "HEAD").split("\0") if item]
    untracked = [item for item in _git_text("ls-files", "--others", "--exclude-standard", "-z").split("\0") if item]
    paths = sorted(set(changed) | set(untracked))
    entries: dict[str, str] = {}
    for relative in paths:
        path = ROOT / relative
        try:
            entries[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError:
            entries[relative] = "deleted-or-unreadable"
    aggregate = hashlib.sha256(
        json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {
        "revision": revision,
        "aggregateSha256": aggregate,
        "pathCount": len(entries),
        "paths": entries,
    }


def _reserve_ports() -> tuple[int, int]:
    # The issuer and exact redirect URI must be known before Keycloak imports the realm.
    with socket.socket() as frontend, socket.socket() as keycloak:
        frontend.bind(("127.0.0.1", 0))
        keycloak.bind(("127.0.0.1", 0))
        return frontend.getsockname()[1], keycloak.getsockname()[1]


def _temp_root() -> Path:
    """Use only an explicit environment override or the platform temporary directory."""
    configured = os.environ.get("P6_TMP_ROOT")
    if configured:
        path = Path(configured).expanduser()
        path.mkdir(parents=True, exist_ok=True)
        return path
    return Path(tempfile.gettempdir())


def _write_override(path: Path, project: str) -> list[str]:
    app_tag = f"procureflow-p6-{project[-10:]}-app:local"
    tags = {
        "postgres": f"procureflow-p6-{project[-10:]}-postgres:local",
        "app": app_tag,
        # The one-shot migrate service runs the same application image; it must use the same
        # per-run tag so the check never rebuilds or leaves the shared baseline image.
        "migrate": app_tag,
        "frontend": f"procureflow-p6-{project[-10:]}-frontend:local",
        "notification-worker": f"procureflow-p6-{project[-10:]}-worker:local",
    }
    lines = ["services:"]
    for service, tag in tags.items():
        lines.extend([f"  {service}:", f"    image: {tag}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return list(dict.fromkeys(tags.values()))


@dataclass
class SuiteOutcome:
    exit_code: int
    duration_seconds: float
    stdout: str = ""
    stderr: str = ""


@dataclass
class ComposeFixture:
    """Owns exactly one generated Compose project and its unique image tags."""

    project: str
    environment: dict[str, str]
    override_path: Path
    secrets: list[str] = field(default_factory=list)
    tags: list[str] = field(default_factory=list)
    addresses: dict[str, int] = field(default_factory=dict)
    started_at: str = field(default_factory=_timestamp)
    build_seconds: float = 0.0
    readiness_seconds: float = 0.0
    failure_text: str = ""
    runtime: dict[str, str] = field(default_factory=dict)

    @classmethod
    def create(cls, root: Path, temp_dir: Path) -> "ComposeFixture":
        suffix = uuid.uuid4().hex[:10]
        project = f"procureflow-p6-{suffix}"
        environment = dict(os.environ)
        for name in (
            "POSTGRES_PASSWORD",
            "APP_DB_PASSWORD",
            "MIGRATION_DB_PASSWORD",
            "WORKER_DB_PASSWORD",
            "RABBITMQ_PASSWORD",
            "KEYCLOAK_DB_PASSWORD",
            "KEYCLOAK_ADMIN_PASSWORD",
            "DEMO_PASSWORD",
        ):
            environment[name] = secrets.token_hex(24)
        environment["COMPOSE_PROJECT_NAME"] = project
        environment["POSTGRES_PORT"] = "0"
        environment["APP_PORT"] = "0"
        environment["WORKER_PORT"] = "0"
        environment["RABBITMQ_AMQP_PORT"] = "0"
        environment["RABBITMQ_MANAGEMENT_PORT"] = "0"
        environment["MAILPIT_SMTP_PORT"] = "0"
        environment["MAILPIT_UI_PORT"] = "0"
        frontend_port, keycloak_port = _reserve_ports()
        environment["FRONTEND_PORT"] = str(frontend_port)
        environment["KEYCLOAK_PORT"] = str(keycloak_port)

        override_path = temp_dir / "compose.override.yaml"
        tags = _write_override(override_path, project)
        return cls(
            project=project,
            environment=environment,
            override_path=override_path,
            secrets=secret_values(environment),
            tags=tags,
        )

    @property
    def compose_argv(self) -> list[str]:
        return [
            "docker", "compose",
            "-f", str(ROOT / "compose.yaml"),
            "-f", str(self.override_path),
            *SERVICE_COMMANDS,
        ]

    def compose(self, *arguments: str, timeout: float, operation: str) -> subprocess.CompletedProcess[str]:
        return run_command(
            [*self.compose_argv, *arguments],
            cwd=ROOT,
            environment=self.environment,
            timeout=timeout,
            operation=operation,
        )

    def _service_port(self, service: str, container_port: int, deadline: Deadline) -> int:
        last_error = "unavailable"
        while not deadline.expired():
            remaining = deadline.remaining()
            if remaining <= 0:
                break
            result = self.compose(
                "port", service, str(container_port),
                timeout=min(20.0, remaining), operation=f"port lookup for {service}",
            )
            if result.returncode == 0:
                address = result.stdout.strip()
                host, _, port_text = address.rpartition(":")
                if host in ("127.0.0.1", "localhost") and port_text.isdigit() and int(port_text) > 0:
                    if deadline.expired():
                        raise VerificationTimeout(
                            f"The bounded {service} port lookup completed after its deadline"
                        )
                    return int(port_text)
                last_error = "not loopback"
            time.sleep(min(1.0, max(0.0, deadline.remaining())))
        raise VerificationTimeout(f"The bounded {service} port lookup failed ({last_error})")

    def _verify_migrate_service(self, deadline: Deadline) -> None:
        """The one-shot migration service must have completed successfully, not merely started."""
        last_error = "not observed"
        while not deadline.expired():
            remaining = deadline.remaining()
            if remaining <= 0:
                break
            result = self.compose(
                "ps", "--all", "--format", "json", MIGRATE_SERVICE,
                timeout=min(10.0, remaining), operation="Migrate service inventory",
            )
            if result.returncode == 0:
                try:
                    entries = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
                except ValueError:
                    raise VerificationError("The migrate service inventory was not valid JSON") from None
                if (len(entries) == 1 and entries[0].get("State") == "exited"
                        and entries[0].get("ExitCode") == 0):
                    return
                last_error = "not exited 0"
            else:
                last_error = f"exit {result.returncode}"
            time.sleep(min(1.0, max(0.0, deadline.remaining())))
        raise VerificationTimeout(
            f"The one-shot migrate service did not complete successfully ({last_error})")

    def _wait_http(self, label: str, url: str, deadline: Deadline,
                   validate: Callable[[str], bool]) -> None:
        last_error = "no response"
        while not deadline.expired():
            remaining = deadline.remaining()
            if remaining <= 0:
                break
            try:
                with urllib.request.urlopen(url, timeout=max(0.1, min(5.0, remaining))) as response:
                    body = response.read().decode()
                    status = response.status
                if status == 200 and validate(body):
                    # A positive response that arrived after the bound is still a timeout.
                    if deadline.expired():
                        raise VerificationTimeout(
                            f"Bounded readiness for {label} completed after its deadline"
                        )
                    return
                last_error = f"HTTP {status}"
            except VerificationTimeout:
                raise
            except (urllib.error.URLError, OSError, ValueError) as error:
                last_error = type(error).__name__
            time.sleep(min(1.0, max(0.0, deadline.remaining())))
        raise VerificationTimeout(f"Bounded readiness failed for {label} ({last_error})")

    def prepare(self, deadline: Deadline, browser_executable: str | None) -> None:
        # Engine and tool checks are explicit, never an assumed daemon.
        result = run_command(
            ["docker", "info"], cwd=ROOT, environment=self.environment,
            timeout=deadline.require("Docker engine check"), operation="Docker engine check",
        )
        _require_success(result, "Docker engine check")
        result = run_command(
            ["docker", "compose", "version"], cwd=ROOT, environment=self.environment,
            timeout=deadline.require("Compose version check"), operation="Compose version check",
        )
        _require_success(result, "Compose version check")
        self._verify_dependencies(browser_executable)

        result = self.compose("config", "--quiet", timeout=deadline.require("Compose configuration check"),
                              operation="Compose configuration check")
        _require_success(result, "Compose configuration check")

        started = time.monotonic()
        result = self.compose("build", timeout=deadline.require("Compose image build", 10),
                              operation="Compose image build")
        _require_success(result, "Compose image build")
        self.build_seconds = round(time.monotonic() - started, 2)

        result = self.compose(
            "up", "--wait", "--wait-timeout", "240", *RUNNING_SERVICE_TARGETS,
            timeout=deadline.require("Compose startup", 10), operation="Compose startup",
        )
        _require_success(result, "Compose startup")
        self._verify_migrate_service(Deadline(min(deadline.remaining(), 30)))

        started = time.monotonic()
        port_deadline = Deadline(min(deadline.remaining(), 90))
        self.addresses = {
            "app": self._service_port("app", 8080, port_deadline),
            "worker": self._service_port("notification-worker", 8081, port_deadline),
            "frontend": self._service_port("frontend", 8080, port_deadline),
            "keycloak": self._service_port("keycloak", 8080, port_deadline),
        }
        if self.addresses["frontend"] != int(self.environment["FRONTEND_PORT"]):
            raise VerificationError("The frontend published a different port than the reserved redirect origin")
        if self.addresses["keycloak"] != int(self.environment["KEYCLOAK_PORT"]):
            raise VerificationError("Keycloak published a different port than the pre-import issuer origin")

        maybe_services = self.compose(
            "ps", "--format", "json",
            timeout=deadline.require("Service inventory"), operation="Service inventory",
        )
        _require_success(maybe_services, "Service inventory")
        services = {
            entry.get("Service")
            for line in maybe_services.stdout.splitlines() if line.strip()
            for entry in [json.loads(line)]
            if entry.get("State") == "running" and entry.get("Health") in (None, "", "healthy")
        }
        if services != EXPECTED_SERVICES:
            raise VerificationError("The disposable stack did not expose exactly the seven healthy services")

        app = f"http://127.0.0.1:{self.addresses['app']}"
        worker = f"http://127.0.0.1:{self.addresses['worker']}"
        frontend = f"http://127.0.0.1:{self.addresses['frontend']}"
        keycloak = f"http://127.0.0.1:{self.addresses['keycloak']}"
        self._wait_http("application readiness", f"{app}/actuator/health/readiness",
                        Deadline(min(deadline.remaining(), 120)),
                        lambda body: json.loads(body).get("status") == "UP")
        self._wait_http("worker readiness", f"{worker}/actuator/health/readiness",
                        Deadline(min(deadline.remaining(), 120)),
                        lambda body: json.loads(body).get("status") == "UP")
        self._wait_http("frontend readiness", f"{frontend}/api/system",
                        Deadline(min(deadline.remaining(), 120)),
                        lambda body: json.loads(body).get("name") == "ProcureFlow")
        self._wait_http("Keycloak discovery", f"{keycloak}/realms/procureflow/.well-known/openid-configuration",
                        Deadline(min(deadline.remaining(), 120)),
                        lambda body: "/realms/procureflow" in json.loads(body).get("issuer", ""))
        self.readiness_seconds = round(time.monotonic() - started, 2)

    def _verify_dependencies(self, browser_executable: str | None) -> None:
        package = E2E / "package.json"
        lockfile = E2E / "package-lock.json"
        installed = E2E / "node_modules" / PLAYWRIGHT_PACKAGE / "package.json"
        if _read_version(package) is None:
            raise VerificationError("e2e/package.json is missing or invalid")
        package_data = json.loads(package.read_text(encoding="utf-8"))
        declared = (package_data.get("devDependencies") or {}).get(PLAYWRIGHT_PACKAGE)
        if declared != PLAYWRIGHT_VERSION:
            raise VerificationError("The E2E package does not declare the pinned Playwright version")
        try:
            lock = json.loads(lockfile.read_text(encoding="utf-8"))
            locked = lock["packages"]["node_modules/@playwright/test"]
        except (OSError, ValueError, KeyError, TypeError):
            raise VerificationError("The E2E lockfile is missing the pinned Playwright package") from None
        if locked.get("version") != PLAYWRIGHT_VERSION or not locked.get("integrity"):
            raise VerificationError("The resolved E2E lock entry does not match the pinned version")
        if _read_version(installed) != PLAYWRIGHT_VERSION:
            raise VerificationError("Run `npm ci` in e2e/ before the browser verifier")
        node = run_command(
            ["node", "--version"], cwd=E2E, environment=self.environment,
            timeout=20, operation="Node version check",
        )
        _require_success(node, "Node version check")
        if not node.stdout.strip().startswith("v22."):
            raise VerificationError("The E2E suite requires Node 22")
        npm = run_command(
            ["npm", "--version"], cwd=E2E, environment=self.environment,
            timeout=20, operation="npm version check",
        )
        _require_success(npm, "npm version check")
        self.runtime = {"node": node.stdout.strip(), "npm": npm.stdout.strip()}
        if browser_executable is not None:
            candidate = Path(browser_executable).expanduser()
            if not candidate.is_file() or not os.access(candidate, os.X_OK):
                raise VerificationError("The explicit browser executable is not an executable file")
            return
        probe = run_command(
            ["node", "-e",
             "const fs=require('fs');const {chromium}=require('playwright');"
             "process.exit(fs.existsSync(chromium.executablePath())?0:1)"],
            cwd=E2E, environment=self.environment, timeout=30,
            operation="Pinned Chromium availability check",
        )
        if probe.returncode != 0:
            raise VerificationError(
                "The pinned Playwright Chromium is not installed; run `npx playwright install chromium` in e2e/"
            )

    def run_suite(self, deadline: Deadline, browser_executable: str | None) -> SuiteOutcome:
        facts_file = self.failure_dir / "suite-facts.json"
        result_file = self.failure_dir / "playwright-result.json"
        output_dir = self.failure_dir / "playwright-results"
        tool = E2E / "node_modules" / ".bin" / "playwright"
        if not tool.is_file():
            raise VerificationError("The pinned Playwright CLI is not installed")
        environment = dict(self.environment)
        environment.update({
            "P6_BASE_URL": f"http://localhost:{self.addresses['frontend']}",
            "P6_KEYCLOAK_URL": f"http://localhost:{self.addresses['keycloak']}",
            "P6_RUN_ID": self.project,
            "P6_DEMO_PASSWORD": self.environment["DEMO_PASSWORD"],
            "P6_FACTS_FILE": str(facts_file),
            "P6_PLAYWRIGHT_JSON": str(result_file),
            "P6_PLAYWRIGHT_OUTPUT": str(output_dir),
        })
        if browser_executable is not None:
            environment["P6_BROWSER_EXECUTABLE"] = str(Path(browser_executable).expanduser())
        started = time.monotonic()
        result = run_command(
            [str(tool), "test", "--config", "playwright.config.ts"],
            cwd=E2E, environment=environment,
            timeout=deadline.require("Playwright suite", 10), operation="Playwright suite",
        )
        return SuiteOutcome(
            exit_code=result.returncode,
            duration_seconds=round(time.monotonic() - started, 2),
            stdout=result.stdout,
            stderr=result.stderr,
        )

    @property
    def failure_dir(self) -> Path:
        return self.override_path.parent

    def cleanup(self, deadline: Deadline) -> dict[str, Any]:
        """Tear down only this owned project inside one strict, non-expanding budget.

        Each command receives at most the remaining deadline. Once it expires, later steps
        record a bounded error instead of opening a fresh five-second window.
        """
        errors: list[str] = []

        def timeout_for(operation: str, cap: float) -> float | None:
            try:
                return min(cap, deadline.require(operation))
            except VerificationTimeout:
                errors.append(f"{operation}: deadline elapsed")
                return None

        timeout = timeout_for("owned Compose teardown", 120.0)
        if timeout is not None:
            try:
                result = self.compose(
                    "down", "--volumes", "--remove-orphans",
                    timeout=timeout, operation="owned Compose teardown",
                )
                if result.returncode != 0:
                    errors.append(f"Compose teardown exited {result.returncode}")
            except (VerificationError, OSError) as error:
                errors.append(type(error).__name__)
                self.failure_text = str(error)

        images_removed = 0
        for tag in self.tags:
            # A setup failure before the build has no owned image; that is not a cleanup failure.
            timeout = timeout_for("owned image check", 30.0)
            if timeout is None:
                break
            existed = run_command(
                ["docker", "image", "inspect", tag], cwd=ROOT, environment=self.environment,
                timeout=timeout, operation="owned image check",
            )
            if existed.returncode != 0:
                continue
            timeout = timeout_for("owned image removal", 60.0)
            if timeout is None:
                break
            result = run_command(
                ["docker", "image", "rm", "-f", tag], cwd=ROOT, environment=self.environment,
                timeout=timeout, operation="owned image removal",
            )
            if result.returncode == 0:
                images_removed += 1
            else:
                errors.append(f"Owned image tag was not removed: {tag}")

        observed: dict[str, int] = {}
        for kind, command in (
            ("containers", ["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={self.project}"]),
            ("volumes", ["docker", "volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={self.project}"]),
            ("networks", ["docker", "network", "ls", "-q", "--filter", f"label=com.docker.compose.project={self.project}"]),
        ):
            timeout = timeout_for(f"owned {kind} verification", 60.0)
            if timeout is None:
                observed[kind] = -1
                continue
            result = run_command(
                command, cwd=ROOT, environment=self.environment,
                timeout=timeout, operation=f"owned {kind} verification",
            )
            if result.returncode != 0:
                errors.append(f"Could not verify owned {kind}")
                observed[kind] = -1
                continue
            count = len([line for line in result.stdout.splitlines() if line.strip()])
            observed[kind] = count
            if count != 0:
                errors.append(f"Owned {kind} remain after cleanup: {count}")
        for tag in self.tags:
            timeout = timeout_for("owned image verification", 30.0)
            if timeout is None:
                break
            result = run_command(
                ["docker", "image", "inspect", tag], cwd=ROOT, environment=self.environment,
                timeout=timeout, operation="owned image verification",
            )
            if result.returncode == 0:
                errors.append(f"Owned image tag still exists: {tag}")
        return {
            "ok": not errors,
            "containers": observed.get("containers", -1),
            "volumes": observed.get("volumes", -1),
            "networks": observed.get("networks", -1),
            "imagesRemoved": images_removed,
            "errors": errors,
        }

    def failure_logs(self, deadline: Deadline) -> str:
        try:
            timeout = min(45.0, deadline.require("failure log collection"))
        except VerificationTimeout:
            return "Failure logs unavailable: deadline elapsed"
        try:
            result = self.compose(
                "logs", "--no-color", "--tail", "120",
                timeout=timeout, operation="failure log collection",
            )
            return result.stdout + result.stderr
        except (VerificationError, OSError) as error:
            return f"Failure logs unavailable: {type(error).__name__}"

    def report_data(self) -> dict[str, Any]:
        return {
            "project": self.project,
            "services": len(EXPECTED_SERVICES),
            "profile": "worker",
            "ports": dict(self.addresses),
            "uniqueImageOverrides": True,
            "buildSeconds": self.build_seconds,
            "readinessSeconds": self.readiness_seconds,
            "runtime": dict(self.runtime),
        }


def _empty_results() -> dict[str, Any]:
    return {"available": False, "tests": [], "counts": {"passed": 0, "failed": 0, "skipped": 0},
            "errors": [], "malformed": True}


def _duration_ms(value: Any) -> int | None:
    """Return a non-negative finite duration, or None when the evidence is invalid."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, float):
        if not math.isfinite(value) or value < 0:
            return None
        return int(value)
    return None


def _safe_duration_seconds(value: Any) -> float:
    """Serialize only a finite non-negative suite duration into the report."""
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError):
        return 0.0
    if not math.isfinite(number) or number < 0:
        return 0.0
    return round(number, 2)


def _playwright_summary(result_file: Path) -> dict[str, Any]:
    """Parse Playwright's JSON without ever turning malformed evidence into a pass."""
    malformed = False
    try:
        data = json.loads(result_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return _empty_results()
    if not isinstance(data, dict):
        return _empty_results()
    tests: list[dict[str, Any]] = []
    errors: list[str] = []
    suites = data.get("suites", [])
    if suites is None:
        suites = []
    if not isinstance(suites, list):
        malformed = True
        suites = []

    def visit(suite: Any) -> None:
        nonlocal malformed
        if not isinstance(suite, dict):
            malformed = True
            return
        specs = suite.get("specs", []) or []
        if not isinstance(specs, list):
            malformed = True
            specs = []
        for spec in specs:
            if not isinstance(spec, dict):
                malformed = True
                continue
            title = spec.get("title")
            if not isinstance(title, str) or not title:
                malformed = True
                title = "untitled"
            statuses: list[Any] = []
            duration = 0
            spec_tests = spec.get("tests", []) or []
            if not isinstance(spec_tests, list):
                malformed = True
                spec_tests = []
            for test in spec_tests:
                if not isinstance(test, dict):
                    malformed = True
                    continue
                results = test.get("results", []) or []
                if not isinstance(results, list):
                    malformed = True
                    results = []
                for result in results:
                    if not isinstance(result, dict):
                        malformed = True
                        continue
                    statuses.append(result.get("status"))
                    parsed_duration = _duration_ms(result.get("duration", 0))
                    if parsed_duration is None:
                        malformed = True
                    else:
                        duration += parsed_duration
            if not statuses:
                malformed = True
                status = "failed"
            elif all(item == "passed" for item in statuses):
                status = "passed"
            elif all(item == "skipped" for item in statuses):
                status = "skipped"
            else:
                status = "failed"
            tests.append({"title": title, "status": status, "durationMs": duration})
        children = suite.get("suites", []) or []
        if not isinstance(children, list):
            malformed = True
            children = []
        for child in children:
            visit(child)

    for suite in suites:
        visit(suite)
    raw_errors = data.get("errors", []) or []
    if not isinstance(raw_errors, list):
        malformed = True
        raw_errors = []
    for error in raw_errors:
        message = error.get("message") if isinstance(error, dict) else str(error)
        if message:
            errors.append(str(message))
    counts = {name: sum(1 for test in tests if test["status"] == name)
              for name in ("passed", "failed", "skipped")}
    return {"available": True, "tests": tests, "counts": counts, "errors": errors, "malformed": malformed}


@dataclass(frozen=True)
class ExpectedTest:
    id: str
    title: str
    checks: tuple[str, ...]


def load_test_contract(path: Path | None = None) -> list[ExpectedTest]:
    """Load the single shared seven-journey contract, failing closed on any deviation."""
    target = path or CRITICAL_CONTRACT
    try:
        data = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise VerificationError("The critical test contract is missing or unreadable") from None
    schema_version = data.get("schemaVersion") if isinstance(data, dict) else None
    if not isinstance(data, dict) or not isinstance(schema_version, int) or isinstance(schema_version, bool) \
            or schema_version != 1:
        raise VerificationError("The critical test contract has an unsupported schema")
    entries = data.get("tests")
    if not isinstance(entries, list) or len(entries) != 7:
        raise VerificationError("The critical test contract must declare exactly seven journeys")
    expected: list[ExpectedTest] = []
    identities: set[str] = set()
    titles: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise VerificationError("The critical test contract contains a malformed journey")
        identifier = entry.get("id")
        title = entry.get("title")
        checks = entry.get("checks")
        if not isinstance(identifier, str) or not identifier or identifier in identities:
            raise VerificationError("The critical test contract contains a duplicate or empty identity")
        if not isinstance(title, str) or not title or title in titles:
            raise VerificationError("The critical test contract contains a duplicate or empty title")
        if (not isinstance(checks, list) or not checks
                or any(not isinstance(name, str) or not name for name in checks)
                or len(set(checks)) != len(checks)):
            raise VerificationError("The critical test contract contains invalid fact requirements")
        identities.add(identifier)
        titles.add(title)
        expected.append(ExpectedTest(identifier, title, tuple(checks)))
    return expected


_MISSING = object()
FACT_INVARIANTS: tuple[tuple[str, Any], ...] = (
    ("checks.employeeLifecycle.invalidPrecisionWrote", False),
    ("checks.employeeLifecycle.selfApprovalStatus", 403),
    ("checks.approvalTimeline.actions", [
        "request.created",
        "request.edited",
        "request.submitted",
        "request.department_approved",
        "request.approved",
    ]),
    ("checks.insufficientBudgetDenial.status", "AWAITING_FINANCE_APPROVAL"),
    ("checks.budgetRaiseAndRetry.status", "APPROVED"),
    ("checks.rejection.status", "REJECTED"),
    ("checks.warmDeepLink.refreshedClean", True),
    ("checks.coldDeepLink.authRequest.redirectUriExactRoot", True),
    ("checks.coldDeepLink.authRequest.responseTypeCode", True),
    ("checks.coldDeepLink.authRequest.codeChallengeMethodS256", True),
    ("checks.coldDeepLink.afterReady.clean", True),
    ("checks.foreignDirectUuid.status", 404),
    ("checks.foreignDirectUuid.contentDisclosed", False),
    ("notifications.employee.readPersisted", True),
    ("checks.revocationWithUnexpiredJwt.capturedBeforeAdminWrite", True),
    ("checks.revocationWithUnexpiredJwt.urlUnchangedBeforeRefresh", True),
    ("checks.revocationWithUnexpiredJwt.mountedRefreshStatus", 403),
    ("checks.revocationWithUnexpiredJwt.interceptorBearerPresent", True),
    ("checks.revocationWithUnexpiredJwt.presentedJwtFingerprintUnchanged", True),
    ("checks.revocationWithUnexpiredJwt.oldJwtExpiredAtDenial", False),
    ("checks.revocationWithUnexpiredJwt.oldJwtFingerprintUnchanged", True),
    ("checks.revocationWithUnexpiredJwt.deniedInboxStatus", 403),
    ("checks.revocationWithUnexpiredJwt.remainingRows", 0),
    ("checks.revocationWithUnexpiredJwt.sessionForbiddenAfterRefresh", True),
    ("checks.revocationWithUnexpiredJwt.rolesUnavailableAfterRefresh", True),
    ("checks.revocationWithUnexpiredJwt.approvalsNavigationRemoved", True),
    ("checks.revocationWithUnexpiredJwt.navigationAfterRefresh", True),
)


def _fact_value(facts: Mapping[str, Any], dotted: str) -> Any:
    current: Any = facts
    for part in dotted.split("."):
        if not isinstance(current, dict) or part not in current:
            return _MISSING
        current = current[part]
    return current


def _fact_matches(actual: Any, wanted: Any) -> bool:
    """Compare factual evidence with its declared type; bool is not a count."""
    if isinstance(wanted, bool):
        return actual is wanted
    if isinstance(wanted, int):
        return isinstance(actual, int) and not isinstance(actual, bool) and actual == wanted
    if isinstance(wanted, str):
        return isinstance(actual, str) and actual == wanted
    if isinstance(wanted, list):
        return isinstance(actual, list) and actual == wanted
    return actual == wanted


def _facts_problems(facts: Any, project: str, expected: list[ExpectedTest]) -> list[str]:
    if not isinstance(facts, dict):
        return ["the sanitized suite facts are missing or malformed"]
    problems: list[str] = []
    run = facts.get("run")
    if not isinstance(run, dict):
        problems.append("the suite facts have no run metadata")
    else:
        if run.get("label") != project:
            problems.append("the suite facts do not belong to the owned Compose project")
        for field_name in ("month", "browserVersion"):
            if not isinstance(run.get(field_name), str) or not run.get(field_name):
                problems.append(f"the suite facts have no {field_name}")
        if run.get("playwrightVersion") != PLAYWRIGHT_VERSION:
            problems.append("the suite facts do not name the pinned Playwright version")
    checks = facts.get("checks")
    if not isinstance(checks, dict):
        problems.append("the suite facts have no checks section")
        return problems
    for entry in expected:
        for name in entry.checks:
            if not isinstance(checks.get(name), dict):
                problems.append(f"the suite facts are missing the {name} check")
    for dotted, wanted in FACT_INVARIANTS:
        actual = _fact_value(facts, dotted)
        if actual is _MISSING:
            problems.append(f"the suite facts are missing {dotted}")
        elif not _fact_matches(actual, wanted):
            problems.append(f"the suite fact {dotted} does not match the expected contract")
    rows_before = _fact_value(facts, "checks.revocationWithUnexpiredJwt.rowsBefore")
    if not isinstance(rows_before, int) or isinstance(rows_before, bool) or rows_before < 1:
        problems.append("the revocation facts do not prove mounted rows before the denial")
    return problems


def _evidence_problems(
    expected: list[ExpectedTest],
    results: dict[str, Any],
    facts: Any,
    project: str,
) -> list[str]:
    problems: list[str] = []
    if not results.get("available"):
        problems.append("the Playwright JSON result is unavailable")
    if results.get("malformed"):
        problems.append("the Playwright JSON result is malformed")
    tests = results.get("tests")
    if not isinstance(tests, list) or not tests:
        problems.append("the Playwright JSON result contains no tests")
    else:
        titles: list[str] = []
        for test in tests:
            if not isinstance(test, dict) or not isinstance(test.get("title"), str):
                problems.append("the Playwright JSON result contains a malformed test entry")
                continue
            titles.append(test["title"])
            if test.get("status") != "passed":
                problems.append(f"the reported test {test['title']!r} did not pass")
        expected_titles = [entry.title for entry in expected]
        if len(titles) != len(expected_titles):
            problems.append(
                f"the suite reported {len(titles)} results instead of {len(expected_titles)}"
            )
        if len(set(titles)) != len(titles):
            problems.append("the Playwright JSON result contains duplicate test identities")
        missing = [title for title in expected_titles if title not in titles]
        unexpected = [title for title in titles if title not in expected_titles]
        if missing:
            problems.append(f"the expected critical tests are missing: {', '.join(sorted(missing))}")
        if unexpected:
            problems.append(f"unexpected critical tests were reported: {', '.join(sorted(unexpected))}")
    counts = results.get("counts")
    expected_counts = {"passed": len(expected), "failed": 0, "skipped": 0}
    if counts != expected_counts:
        problems.append(
            "the result counts do not prove all seven passes with zero failures and skips"
        )
    errors = results.get("errors")
    if not isinstance(errors, list):
        problems.append("the Playwright JSON result has a malformed global error list")
    elif errors:
        problems.append(f"the Playwright JSON result reports {len(errors)} global suite error(s)")
    problems.extend(_facts_problems(facts, project, expected))
    return problems


class Verification:
    """Owns setup/test/cleanup control flow so cleanup is attempted on every path."""

    def __init__(
        self,
        fixture: ComposeFixture,
        *,
        report_path: Path,
        secrets: Iterable[str],
        browser_executable: str | None = None,
        setup_seconds: float = 1200.0,
        test_seconds: float = 600.0,
        cleanup_seconds: float = 180.0,
        artifact_dir: Path | None = None,
    ):
        self.fixture = fixture
        self.report_path = report_path
        self.artifact_dir = artifact_dir or report_path.parent
        self.secrets = list(secrets)
        self.browser_executable = browser_executable
        self.setup_seconds = setup_seconds
        self.test_seconds = test_seconds
        self.cleanup_seconds = cleanup_seconds

    def execute(self) -> int:
        # A previous failed iteration's sanitized diagnostics must not look like this run's proof.
        try:
            (self.artifact_dir / "browser-failure.log").unlink(missing_ok=True)
        except OSError:
            pass
        stage = "setup"
        setup_error: str | None = None
        suite: SuiteOutcome | None = None
        cleanup: dict[str, Any] = {"ok": False, "errors": ["cleanup was not attempted"]}
        failure_log = ""
        started = _timestamp()
        expected: list[ExpectedTest] = []
        contract_error: str | None = None
        results: dict[str, Any] = _empty_results()
        suite_facts: Any = {}
        evidence_problems: list[str] = []

        try:
            try:
                expected = load_test_contract()
            except VerificationError as error:
                contract_error = redact_text(str(error), self.secrets)
                setup_error = f"contract: {contract_error}"

            if setup_error is None:
                try:
                    self.fixture.prepare(Deadline(self.setup_seconds), self.browser_executable)
                    stage = "suite"
                    suite = self.fixture.run_suite(Deadline(self.test_seconds), self.browser_executable)
                except (VerificationError, VerificationTimeout) as error:
                    setup_error = f"{stage}: {redact_text(str(error), self.secrets)}"
                except Exception as error:  # noqa: BLE001 - the report must never include a raw traceback
                    setup_error = f"{stage}: unexpected {type(error).__name__}"

            # Parsing and validation are guarded too: malformed evidence or an unexpected
            # parser/validator exception must never bypass the owned fixture teardown below.
            stage = "evidence"
            parsed_results = _playwright_summary(self.fixture.failure_dir / "playwright-result.json")
            if not isinstance(parsed_results, dict):
                raise VerificationError("The Playwright JSON summary is not an object")
            results = parsed_results
            try:
                suite_facts = json.loads(
                    (self.fixture.failure_dir / "suite-facts.json").read_text(encoding="utf-8")
                )
            except (OSError, ValueError):
                suite_facts = {}

            if setup_error is None and suite is not None:
                validated = _evidence_problems(expected, results, suite_facts, self.fixture.project)
                if not isinstance(validated, list) or any(not isinstance(item, str) for item in validated):
                    raise VerificationError("The evidence validator returned an invalid result")
                evidence_problems = validated
            failing = (
                setup_error is not None
                or (suite is not None and suite.exit_code != 0)
                or bool(evidence_problems)
            )
            if failing:
                try:
                    # Collect while the owned stack still exists; cleanup follows in finally.
                    failure_log = self.fixture.failure_logs(Deadline(45.0))
                except Exception:  # noqa: BLE001 - diagnostics are optional
                    failure_log = ""
        except (KeyboardInterrupt, SystemExit):
            # The owned fixture is still torn down by the finally block; interruption is not
            # converted into a report or silently swallowed.
            raise
        except Exception as error:  # noqa: BLE001 - sanitize unexpected validation failures
            setup_error = setup_error or f"{stage}: unexpected {type(error).__name__}"
            evidence_problems = []
            if not isinstance(results, dict):
                results = _empty_results()
        finally:
            try:
                cleanup = self.fixture.cleanup(Deadline(self.cleanup_seconds))
            except (VerificationError, VerificationTimeout, OSError) as error:
                cleanup = {"ok": False, "errors": [f"cleanup: {type(error).__name__}"],
                           "containers": -1, "volumes": -1, "networks": -1, "imagesRemoved": 0}
            except Exception as error:  # noqa: BLE001 - teardown failure must still produce exit 1
                cleanup = {"ok": False, "errors": [f"cleanup: unexpected {type(error).__name__}"],
                           "containers": -1, "volumes": -1, "networks": -1, "imagesRemoved": 0}
            if not isinstance(cleanup, dict):
                cleanup = {"ok": False, "errors": ["cleanup: invalid result"],
                           "containers": -1, "volumes": -1, "networks": -1, "imagesRemoved": 0}

        cleanup_ok = bool(cleanup.get("ok"))
        passed = (
            setup_error is None
            and contract_error is None
            and suite is not None
            and suite.exit_code == 0
            and not evidence_problems
            and cleanup_ok
        )
        evidence_failure = (
            "suite evidence: " + "; ".join(evidence_problems) if evidence_problems else None
        )
        suite_failure = (
            f"suite: Playwright exited {suite.exit_code}"
            if suite is not None and suite.exit_code != 0 else None
        )
        failure = setup_error or evidence_failure or suite_failure
        if failure_log:
            sanitized_log = redact_text(failure_log, self.secrets)
            try:
                self.artifact_dir.mkdir(parents=True, exist_ok=True)
                (self.artifact_dir / "browser-failure.log").write_text(sanitized_log[-60_000:], encoding="utf-8")
            except OSError:
                pass

        try:
            source = source_manifest()
        except VerificationError as error:
            source = {"error": redact_text(str(error), self.secrets)}
        run_facts = suite_facts.get("run") if isinstance(suite_facts, dict) else None
        browser_version = run_facts.get("browserVersion") if isinstance(run_facts, dict) else None
        report = deep_redact({
            "schemaVersion": 1,
            "phase": "P6.1/P6.3 - committed critical browser suite",
            "startedAt": started,
            "finishedAt": _timestamp(),
            "outcome": "passed" if passed else "failed",
            "exitCode": 0 if passed else 1,
            "source": source,
            "stack": self.fixture.report_data(),
            "playwright": {
                "package": PLAYWRIGHT_PACKAGE,
                "version": PLAYWRIGHT_VERSION,
                "browser": "chromium",
                "browserVersion": browser_version,
                "executable": "explicit-override" if self.browser_executable else "playwright-managed",
                "trace": "disabled",
                "video": "disabled",
                "har": "disabled",
                "storageState": "disabled",
            },
            "log": "browser-run.log",
            "verification": {
                "contract": "e2e/critical-tests.json",
                "expectedTests": [{"id": entry.id, "title": entry.title} for entry in expected],
                "contractError": contract_error,
                "evidenceProblems": evidence_problems,
            },
            "suite": {
                "facts": suite_facts,
                "durationSeconds": _safe_duration_seconds(suite.duration_seconds) if suite else 0.0,
                "results": results,
            },
            "failure": failure,
            "cleanup": cleanup,
            "limitations": [
                "Chromium only; no cross-browser, availability, throughput or hosted-runner claim.",
                "Linux GitHub-hosted execution is configured but its result is not part of this local run.",
                "The existing fault/recovery checker remains P6.2 and is not duplicated here.",
                "No trace, video, HAR or storage state is retained on failure.",
                "The optional stale two-context edit scenario is not included in this bounded suite.",
            ],
        }, self.secrets)

        log_lines = [
            f"outcome={report['outcome']} exitCode={report['exitCode']}",
            f"testedRevision={report['source']['revision']}",
            f"dirtyManifest={report['source']['aggregateSha256']} paths={report['source']['pathCount']}",
            f"cleanup={json.dumps(report['cleanup'], sort_keys=True)}",
            f"evidenceProblems={len(evidence_problems)}",
        ]
        if suite is not None:
            log_lines.append(f"suiteSeconds={suite.duration_seconds}")
            log_lines.append(redact_text(suite.stdout, self.secrets))
            log_lines.append(redact_text(suite.stderr, self.secrets))
        if failure_log:
            log_lines.append(redact_text(failure_log, self.secrets))
        try:
            self.artifact_dir.mkdir(parents=True, exist_ok=True)
            (self.artifact_dir / "browser-run.log").write_text(
                "\n".join(item for item in log_lines if item)[-80_000:] + "\n", encoding="utf-8",
            )
            self.report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError:
            print("The sanitized browser report could not be written", file=sys.stderr)
            return 1

        summary = report.get("suite", {}).get("results", {})
        counts = summary.get("counts", {}) if isinstance(summary, dict) else {}
        print(
            "browser verification "
            f"{report['outcome']}: passed={counts.get('passed', 0)} "
            f"failed={counts.get('failed', 0)} skipped={counts.get('skipped', 0)} "
            f"cleanup={'ok' if cleanup.get('ok') else 'failed'} "
            f"owned={'0' if cleanup.get('ok') else 'check-report'}"
        )
        if suite is not None and suite.stdout:
            print(redact_text(suite.stdout, self.secrets)[-20_000:])
        if suite is not None and suite.stderr:
            print(redact_text(suite.stderr, self.secrets)[-8_000:], file=sys.stderr)
        if setup_error is not None:
            print(f"verification failed: {setup_error}", file=sys.stderr)
        for problem in evidence_problems:
            print(f"verification failed: suite evidence: {problem}", file=sys.stderr)
        if cleanup.get("ok") is False:
            print(f"verification failed: cleanup: {cleanup.get('errors')}", file=sys.stderr)
        print(f"sanitized report: {self.report_path}")
        return 0 if passed else 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the critical ProcureFlow Playwright suite on a disposable seven-service stack."
    )
    parser.add_argument(
        "--browser-executable",
        help="Explicit Chromium executable path; defaults to the pinned Playwright installation.",
    )
    parser.add_argument(
        "--report",
        default=str(REPORT_DIR / "browser-run.json"),
        help="Sanitized JSON report path (default: target/p6-browser/browser-run.json).",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    arguments = parse_args(argv)
    temp_dir: Path | None = None
    try:
        temp_dir = Path(tempfile.mkdtemp(prefix="procureflow-p6-", dir=_temp_root()))
        fixture = ComposeFixture.create(ROOT, temp_dir)
        return Verification(
            fixture,
            report_path=Path(arguments.report),
            secrets=fixture.secrets,
            browser_executable=arguments.browser_executable,
        ).execute()
    except (VerificationError, OSError) as error:
        sanitized = redact_text(str(error))
        print(f"browser verification could not start: {sanitized}", file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 - never print an arbitrary traceback with local state
        print(f"browser verification could not start: unexpected {type(error).__name__}", file=sys.stderr)
        return 1
    finally:
        # Playwright output contains raw error-context files. They are intentionally ephemeral.
        if temp_dir is not None:
            import shutil
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
