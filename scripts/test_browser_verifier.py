"""Focused unit checks for the P6 browser runner's evidence, safety and cleanup control flow.

These tests use fakes only: no container, browser, network or database is contacted. They
protect fail-closed evidence validation, redaction (generated secrets, bearer values, callback
values, cookie/authorization headers in text and structured report values), monotonic deadlines,
bounded cleanup and the ownership rule that a timeout kills only the command tree it started.
"""

from __future__ import annotations

import contextlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

MODULE_PATH = Path(__file__).resolve().with_name("check-browser.py")
SPEC = importlib.util.spec_from_file_location("procureflow_check_browser", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("The browser verifier module could not be loaded")
browser_verifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = browser_verifier
SPEC.loader.exec_module(browser_verifier)

CONTRACT_PATH = MODULE_PATH.parent.parent / "e2e" / "critical-tests.json"
CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
EXPECTED_TITLES = [entry["title"] for entry in CONTRACT["tests"]]

SECRET = "generated-secret-value-0123456789"
JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhLWVtcGxveWVlIn0.signaturevalue"
COOKIE = "fixture-cookie-value-0123456789"


def default_facts(project: str = "procureflow-p6-test") -> dict[str, object]:
    return {
        "run": {
            "label": project,
            "month": "2026-10",
            "startedAt": "2026-10-20T00:00:00.000Z",
            "playwrightVersion": "1.60.0",
            "browserVersion": "148.0.7778.96",
        },
        "checks": {
            "employeeLifecycle": {"invalidPrecisionWrote": False, "selfApprovalStatus": 403},
            "approvalTimeline": {
                "actions": [
                    "request.created",
                    "request.edited",
                    "request.submitted",
                    "request.department_approved",
                    "request.approved",
                ],
            },
            "employeeNotification": {"eventId": "employee-event"},
            "managerNotification": {"eventId": "manager-event"},
            "budget": {"limitCents": 600000},
            "insufficientBudgetDenial": {"status": "AWAITING_FINANCE_APPROVAL"},
            "budgetRaiseAndRetry": {"status": "APPROVED"},
            "rejection": {"status": "REJECTED"},
            "warmDeepLink": {"refreshedClean": True},
            "coldDeepLink": {
                "authRequest": {
                    "redirectUriExactRoot": True,
                    "responseTypeCode": True,
                    "codeChallengeMethodS256": True,
                },
                "afterReady": {"clean": True},
            },
            "foreignDirectUuid": {"status": 404, "contentDisclosed": False},
            "revocationWithUnexpiredJwt": {
                "capturedBeforeAdminWrite": True,
                "urlUnchangedBeforeRefresh": True,
                "mountedRefreshStatus": 403,
                "interceptorBearerPresent": True,
                "presentedJwtFingerprintUnchanged": True,
                "oldJwtExpiredAtDenial": False,
                "oldJwtFingerprintUnchanged": True,
                "deniedInboxStatus": 403,
                "remainingRows": 0,
                "rowsBefore": 2,
                "sessionForbiddenAfterRefresh": True,
                "rolesUnavailableAfterRefresh": True,
                "approvalsNavigationRemoved": True,
                "navigationAfterRefresh": True,
            },
        },
        "requests": {"primary": {"id": "primary-request"}},
        "identities": {"manager": {"fingerprint": "abc", "expired": False}},
        "budgets": {"afterRaise": {"limitCents": 600000}},
        "notifications": {"employee": {"readPersisted": True}},
        "callback": {},
        "limitations": [],
    }


class FakeClock:
    def __init__(self) -> None:
        self.now = 100.0

    def monotonic(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class FakeFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.project = "procureflow-p6-test"
        self.prepared = 0
        self.suite_calls = 0
        self.cleanup_calls = 0
        self.log_calls = 0
        self.prepare_error: Exception | None = None
        self.suite_error: Exception | None = None
        self.suite_exit = 0
        self.cleanup_error: Exception | None = None
        self.extra_report: dict[str, object] = {}
        self.stdout_text = f"list output {SECRET} Bearer {JWT}"
        self.stderr_text = ""
        self.failure_log_text = (
            f"compose log {SECRET}\n"
            f"  - Cookie: KEYCLOAK_SESSION={COOKIE}\n"
            f'{{"Cookie":"KEYCLOAK_SESSION={COOKIE}"}}'
        )
        self.failure_dir.mkdir(parents=True, exist_ok=True)
        self.set_results()
        self.set_facts(default_facts(self.project))

    @property
    def failure_dir(self) -> Path:
        return self.root / "artifacts"

    def set_results(
        self,
        titles: list[str] | None = None,
        statuses: list[str] | str = "passed",
        errors: list[dict[str, str]] | None = None,
        raw: str | None = None,
        durations: list[object] | None = None,
    ) -> None:
        path = self.failure_dir / "playwright-result.json"
        if raw is not None:
            path.write_text(raw, encoding="utf-8")
            return
        selected = list(EXPECTED_TITLES if titles is None else titles)
        status_list = [statuses] * len(selected) if isinstance(statuses, str) else list(statuses)
        if len(status_list) < len(selected):
            status_list += ["passed"] * (len(selected) - len(status_list))
        duration_list = [100 + index for index in range(len(selected))] if durations is None else list(durations)
        if len(duration_list) < len(selected):
            duration_list += [100 + index for index in range(len(duration_list), len(selected))]
        document = {
            "suites": [{
                "specs": [
                    {"title": title, "tests": [{"results": [
                        {"status": status_list[index], "duration": duration_list[index]},
                    ]}]}
                    for index, title in enumerate(selected)
                ],
            }],
            "errors": errors or [],
        }
        path.write_text(json.dumps(document), encoding="utf-8")

    def set_facts(self, facts: object) -> None:
        (self.failure_dir / "suite-facts.json").write_text(json.dumps(facts), encoding="utf-8")

    def prepare(self, deadline: object, browser_executable: str | None) -> None:
        self.prepared += 1
        if self.prepare_error is not None:
            raise self.prepare_error

    def run_suite(self, deadline: object, browser_executable: str | None) -> object:
        self.suite_calls += 1
        if self.suite_error is not None:
            raise self.suite_error
        return browser_verifier.SuiteOutcome(
            self.suite_exit, 0.5, stdout=self.stdout_text, stderr=self.stderr_text,
        )

    def cleanup(self, deadline: object) -> dict[str, object]:
        self.cleanup_calls += 1
        if self.cleanup_error is not None:
            raise self.cleanup_error
        return {"ok": True, "containers": 0, "volumes": 0, "networks": 0, "imagesRemoved": 4, "errors": []}

    def failure_logs(self, deadline: object) -> str:
        self.log_calls += 1
        return self.failure_log_text

    def report_data(self) -> dict[str, object]:
        return {"project": self.project, **self.extra_report}


class VerifierCase(unittest.TestCase):
    def run_verifier(self, fixture: FakeFixture) -> tuple[int, str, str, dict[str, object]]:
        output = io.StringIO()
        errors = io.StringIO()
        report_path = fixture.root / "browser-run.json"
        verifier = browser_verifier.Verification(
            fixture,
            report_path=report_path,
            artifact_dir=fixture.root,
            secrets=[SECRET],
            setup_seconds=5,
            test_seconds=5,
            cleanup_seconds=5,
        )
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = verifier.execute()
        report = json.loads(report_path.read_text(encoding="utf-8"))
        return code, output.getvalue(), errors.getvalue(), report

    def assert_evidence_rejected(self, fixture: FakeFixture) -> dict[str, object]:
        code, _, errors, report = self.run_verifier(fixture)
        self.assertEqual(code, 1, "invalid evidence must never exit zero")
        self.assertEqual(report["outcome"], "failed")
        self.assertTrue(report["cleanup"]["ok"])
        self.assertEqual(fixture.cleanup_calls, 1)
        self.assertTrue(report["verification"]["evidenceProblems"])
        self.assertIn("suite evidence", errors)
        return report


class EvidenceFailClosedTests(VerifierCase):
    def test_success_requires_the_exact_seven_contract_tests_and_facts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            code, output, _, report = self.run_verifier(fixture)
            self.assertEqual(code, 0)
            self.assertEqual(report["outcome"], "passed")
            self.assertEqual(report["suite"]["results"]["counts"], {"passed": 7, "failed": 0, "skipped": 0})
            self.assertEqual(report["verification"]["evidenceProblems"], [])
            self.assertEqual(
                [entry["title"] for entry in report["verification"]["expectedTests"]],
                EXPECTED_TITLES,
            )
            self.assertIn("cleanup=ok", output)

    def test_all_skipped_results_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(statuses="skipped")
            report = self.assert_evidence_rejected(fixture)
            self.assertEqual(report["suite"]["results"]["counts"]["skipped"], 7)

    def test_missing_results_file_is_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            (fixture.failure_dir / "playwright-result.json").unlink()
            report = self.assert_evidence_rejected(fixture)
            self.assertFalse(report["suite"]["results"]["available"])

    def test_malformed_results_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(raw="{not valid json")
            report = self.assert_evidence_rejected(fixture)
            self.assertTrue(report["suite"]["results"]["malformed"])

    def test_non_object_results_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(raw="[]")
            self.assert_evidence_rejected(fixture)

    def test_empty_results_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(titles=[])
            self.assert_evidence_rejected(fixture)

    def test_partial_results_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(titles=EXPECTED_TITLES[:-1])
            self.assert_evidence_rejected(fixture)

    def test_duplicate_identity_cannot_replace_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(titles=EXPECTED_TITLES[:-1] + [EXPECTED_TITLES[0]])
            self.assert_evidence_rejected(fixture)

    def test_one_failed_case_is_not_a_pass_even_with_exit_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            statuses = ["passed"] * 6 + ["failed"]
            fixture.set_results(statuses=statuses)
            self.assert_evidence_rejected(fixture)

    def test_global_suite_error_fails_even_with_seven_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(errors=[{"message": "teardown worker crashed"}])
            self.assert_evidence_rejected(fixture)

    def test_missing_facts_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            (fixture.failure_dir / "suite-facts.json").unlink()
            self.assert_evidence_rejected(fixture)

    def test_wrong_project_facts_are_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_facts(default_facts("another-project"))
            self.assert_evidence_rejected(fixture)

    def test_revocation_facts_must_prove_an_unexpired_denied_jwt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            facts = default_facts()
            checks = facts["checks"]  # type: ignore[index]
            checks["revocationWithUnexpiredJwt"]["oldJwtExpiredAtDenial"] = True  # type: ignore[index]
            fixture.set_facts(facts)
            self.assert_evidence_rejected(fixture)

    def test_boolean_count_cannot_masquerade_as_zero_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            facts = default_facts()
            checks = facts["checks"]  # type: ignore[index]
            checks["revocationWithUnexpiredJwt"]["remainingRows"] = False  # type: ignore[index]
            fixture.set_facts(facts)
            self.assert_evidence_rejected(fixture)

    def test_contract_rejects_a_boolean_schema_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.json"
            path.write_text(json.dumps({"schemaVersion": True, "tests": []}), encoding="utf-8")
            with self.assertRaises(browser_verifier.VerificationError):
                browser_verifier.load_test_contract(path)

    def test_nonzero_suite_exit_is_not_a_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.suite_exit = 1
            code, _, _, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1)
            self.assertEqual(report["outcome"], "failed")
            self.assertGreaterEqual(fixture.log_calls, 1)

    def test_cleanup_failure_overrides_a_green_suite(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.cleanup_error = browser_verifier.VerificationError("owned resources remain")
            code, _, _, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1)
            self.assertFalse(report["cleanup"]["ok"])

    def test_setup_failure_still_cleans_up_and_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.prepare_error = browser_verifier.VerificationError("engine unavailable")
            code, _, _, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1)
            self.assertTrue(str(report["failure"]).startswith("setup:"))

    def test_suite_timeout_still_cleans_up_and_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.suite_error = browser_verifier.VerificationTimeout("suite deadline elapsed")
            code, _, _, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1)
            self.assertEqual(report["outcome"], "failed")


class EvidenceParsingFailureTests(VerifierCase):
    """Malformed or unexpectedly failing evidence must never bypass owned teardown."""

    def _assert_invalid_duration_rejected(self, duration: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.set_results(durations=[duration])
            code, _, errors, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1, "cleanup must run exactly once")
            self.assertEqual(report["outcome"], "failed")
            self.assertTrue(report["cleanup"]["ok"])
            self.assertTrue(report["suite"]["results"]["malformed"])
            self.assertTrue(report["verification"]["evidenceProblems"])
            self.assertIn("suite evidence", errors)
            serialized = json.dumps(report)
            self.assertNotIn("Infinity", serialized)
            self.assertNotIn("NaN", serialized)

    def test_infinite_duration_fails_closed_and_still_cleans_up(self) -> None:
        self._assert_invalid_duration_rejected(float("inf"))

    def test_nan_duration_fails_closed_and_still_cleans_up(self) -> None:
        self._assert_invalid_duration_rejected(float("nan"))

    def test_malformed_duration_fails_closed_and_still_cleans_up(self) -> None:
        for duration in ("not-a-duration", -1):
            with self.subTest(duration=duration):
                self._assert_invalid_duration_rejected(duration)

    def test_unexpected_parser_failure_still_cleans_up_exactly_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            message = f"parser exploded {SECRET} {JWT} {COOKIE}"
            with mock.patch.object(
                browser_verifier, "_playwright_summary", side_effect=RuntimeError(message),
            ):
                code, output, errors, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1, "cleanup must run exactly once")
            self.assertEqual(report["outcome"], "failed")
            self.assertTrue(report["cleanup"]["ok"])
            self.assertIn("evidence: unexpected RuntimeError", report["failure"])
            self._assert_sanitized(report, output, errors, Path(directory))

    def test_unexpected_validator_failure_still_cleans_up_exactly_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            message = f"validator exploded {SECRET} {JWT} {COOKIE}"
            with mock.patch.object(
                browser_verifier, "_evidence_problems", side_effect=RuntimeError(message),
            ):
                code, output, errors, report = self.run_verifier(fixture)
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1, "cleanup must run exactly once")
            self.assertEqual(report["outcome"], "failed")
            self.assertTrue(report["cleanup"]["ok"])
            self.assertIn("evidence: unexpected RuntimeError", report["failure"])
            self._assert_sanitized(report, output, errors, Path(directory))

    def test_keyboard_interrupt_still_attempts_owned_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = FakeFixture(root)
            verifier = browser_verifier.Verification(
                fixture,
                report_path=root / "browser-run.json",
                artifact_dir=root,
                secrets=[SECRET],
                setup_seconds=5,
                test_seconds=5,
                cleanup_seconds=5,
            )
            with mock.patch.object(browser_verifier, "_playwright_summary", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        verifier.execute()
            self.assertEqual(fixture.cleanup_calls, 1, "an interrupted owned run still tears down")

    def test_report_write_failure_still_cleans_up_exactly_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = FakeFixture(root)
            verifier = browser_verifier.Verification(
                fixture,
                report_path=root,  # a directory: writing the report must fail after teardown
                artifact_dir=root,
                secrets=[SECRET],
                setup_seconds=5,
                test_seconds=5,
                cleanup_seconds=5,
            )
            errors = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                code = verifier.execute()
            self.assertEqual(code, 1)
            self.assertEqual(fixture.cleanup_calls, 1, "cleanup must run exactly once")
            self.assertIn("could not be written", errors.getvalue())

    def _assert_sanitized(
        self,
        report: dict[str, object],
        output: str,
        errors: str,
        directory: Path,
    ) -> None:
        serialized = json.dumps(report)
        for value in (SECRET, JWT, COOKIE, "Traceback (most recent call last)"):
            self.assertNotIn(value, serialized)
            self.assertNotIn(value, output)
            self.assertNotIn(value, errors)
            for name in ("browser-run.log", "browser-failure.log"):
                path = directory / name
                if path.exists():
                    self.assertNotIn(value, path.read_text(encoding="utf-8"))


class RedactionTests(unittest.TestCase):
    def test_direct_redaction_handles_cookie_and_auth_header_forms(self) -> None:
        cases = [
            f"  - cookie: KEYCLOAK_SESSION={COOKIE}",
            f"- Set-Cookie: KEYCLOAK_IDENTITY={COOKIE}; Path=/",
            f'{{"Cookie":"KEYCLOAK_SESSION={COOKIE}"}}',
            f'{{"headers":{{"SET-COOKIE":"KEYCLOAK_IDENTITY={COOKIE}"}}}}',
            f'{{\\"Cookie\\":\\"KEYCLOAK_SESSION={COOKIE}\\"}}',
            f"Authorization: Bearer {JWT}",
        ]
        for text in cases:
            with self.subTest(text=text):
                redacted = browser_verifier.redact_text(text, [SECRET])
                self.assertNotIn(COOKIE, redacted)
                self.assertNotIn(JWT, redacted)
                self.assertIn("[REDACTED]", redacted)
        list_form = browser_verifier.redact_text(f"  - cookie: KEYCLOAK_SESSION={COOKIE}")
        self.assertIn("cookie: [REDACTED]", list_form)
        json_form = browser_verifier.redact_text(f'{{"Cookie":"KEYCLOAK_SESSION={COOKIE}"}}')
        self.assertIn('"Cookie":"[REDACTED]"', json_form)

    def test_structured_redaction_removes_sensitive_nested_keys_only(self) -> None:
        redacted = browser_verifier.deep_redact({
            "headers": {"Cookie": COOKIE, "Accept": "application/json"},
            "Authorization": f"Bearer {JWT}",
            "diagnostic": "request approved",
        })
        self.assertEqual(redacted["headers"]["Cookie"], "[REDACTED]")
        self.assertEqual(redacted["headers"]["Accept"], "application/json")
        self.assertEqual(redacted["Authorization"], "[REDACTED]")
        self.assertEqual(redacted["diagnostic"], "request approved")

    def test_ordinary_non_secret_diagnostics_are_preserved(self) -> None:
        text = "readiness http://127.0.0.1:8080/actuator/health status=UP"
        self.assertEqual(browser_verifier.redact_text(text), text)

    def test_callback_query_and_fragment_values_are_removed(self) -> None:
        redacted = browser_verifier.redact_text(
            "callback http://localhost:4200/?code=raw-code&state=raw-state#session_state=raw-fragment"
        )
        for forbidden in ("raw-code", "raw-state", "raw-fragment"):
            self.assertNotIn(forbidden, redacted)
        self.assertIn("[REDACTED_QUERY]", redacted)
        self.assertIn("[REDACTED_FRAGMENT]", redacted)

    def test_full_verification_redacts_every_persisted_and_printed_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = FakeFixture(Path(directory))
            fixture.extra_report = {
                "headers": {"Cookie": COOKIE, "Authorization": f"Bearer {JWT}"},
                "nested": [{"set-cookie": COOKIE}],
                "callback": "http://localhost:4200/?code=raw-code-value&state=raw-state-value",
            }
            fixture.stdout_text = f'list output {SECRET}\n{{"Cookie":"KEYCLOAK_SESSION={COOKIE}"}}'
            fixture.stderr_text = f"  - Set-Cookie: KEYCLOAK_IDENTITY={COOKIE}"
            fixture.failure_log_text = (
                f"compose log {SECRET}\n"
                f"  - cookie: KEYCLOAK_SESSION={COOKIE}\n"
                f'{{"Authorization":"Bearer {JWT}"}}'
            )
            fixture.suite_exit = 1
            code, output, errors, report = self._run_failing(fixture)
            self.assertEqual(code, 1)
            forbidden = (SECRET, JWT, COOKIE, "raw-code-value", "raw-state-value")
            serialized = json.dumps(report)
            for value in forbidden:
                self.assertNotIn(value, serialized)
                self.assertNotIn(value, output)
                self.assertNotIn(value, errors)
                for name in ("browser-run.log", "browser-failure.log"):
                    self.assertNotIn(value, (Path(directory) / name).read_text(encoding="utf-8"))
            self.assertEqual(report["stack"]["headers"]["Cookie"], "[REDACTED]")
            self.assertEqual(report["stack"]["headers"]["Authorization"], "[REDACTED]")

    def _run_failing(self, fixture: FakeFixture) -> tuple[int, str, str, dict[str, object]]:
        verifier = browser_verifier.Verification(
            fixture,
            report_path=fixture.root / "browser-run.json",
            artifact_dir=fixture.root,
            secrets=[SECRET],
            setup_seconds=5,
            test_seconds=5,
            cleanup_seconds=5,
        )
        output = io.StringIO()
        errors = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = verifier.execute()
        return code, output.getvalue(), errors.getvalue(), json.loads(
            (fixture.root / "browser-run.json").read_text(encoding="utf-8")
        )


class DeadlineTests(unittest.TestCase):
    def test_remaining_and_expired_fail_closed(self) -> None:
        clock = FakeClock()
        deadline = browser_verifier.Deadline(10, clock=clock.monotonic)
        self.assertEqual(deadline.remaining(), 10)
        clock.advance(4)
        self.assertEqual(deadline.remaining(), 6)
        clock.advance(6)
        self.assertTrue(deadline.expired())
        with self.assertRaises(browser_verifier.VerificationTimeout):
            deadline.require("bounded operation")
        with self.assertRaises(browser_verifier.VerificationTimeout):
            browser_verifier.bounded_timeout(0, "bounded operation")

    def test_wait_http_rejects_a_positive_response_after_the_deadline(self) -> None:
        class SlowHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - http.server API
                time.sleep(0.5)
                self.send_response(200)
                self.end_headers()
                try:
                    self.wfile.write(b'{"status":"UP"}')
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *_args: object) -> None:
                return

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            fixture = browser_verifier.ComposeFixture.__new__(browser_verifier.ComposeFixture)
            url = f"http://127.0.0.1:{server.server_port}/health"
            with self.assertRaises(browser_verifier.VerificationTimeout):
                fixture._wait_http("late health", url, browser_verifier.Deadline(0.1), lambda body: True)
        finally:
            server.shutdown()
            server.server_close()

    def test_service_port_rejects_success_observed_after_the_deadline(self) -> None:
        class SlowPortFixture(browser_verifier.ComposeFixture):
            def __init__(self, clock: FakeClock) -> None:
                self.clock = clock

            def compose(self, *_arguments: str, timeout: float, operation: str) -> subprocess.CompletedProcess[str]:
                self.clock.advance(30)
                return subprocess.CompletedProcess(["compose"], 0, "127.0.0.1:5555\n", "")

        clock = FakeClock()
        fixture = SlowPortFixture(clock)
        with self.assertRaises(browser_verifier.VerificationTimeout):
            fixture._service_port("app", 8080, browser_verifier.Deadline(10, clock=clock.monotonic))


class CleanupBudgetTests(unittest.TestCase):
    def test_cleanup_never_opens_a_new_window_after_the_deadline(self) -> None:
        clock = FakeClock()
        calls: list[float] = []

        def fake_run(argv: list[str], *, cwd: Path, environment: object, timeout: float,
                     operation: str) -> subprocess.CompletedProcess[str]:
            calls.append(timeout)
            clock.advance(50)
            return subprocess.CompletedProcess(argv, 0, "", "")

        fixture = browser_verifier.ComposeFixture(
            project="procureflow-p6-test",
            environment={},
            override_path=Path("/nonexistent/override.yaml"),
        )
        with mock.patch.object(browser_verifier, "run_command", side_effect=fake_run):
            report = fixture.cleanup(browser_verifier.Deadline(20, clock=clock.monotonic))
        self.assertFalse(report["ok"])
        self.assertEqual(len(calls), 1, "no command may start after the cleanup deadline")
        self.assertLessEqual(calls[0], 20.0)
        self.assertTrue(any("deadline elapsed" in error for error in report["errors"]))


class ProcessOwnershipTests(unittest.TestCase):
    @staticmethod
    def _write_scripts(directory: Path) -> tuple[Path, Path]:
        grandchild = directory / "grandchild.py"
        grandchild.write_text(
            "import os, sys, time\n"
            "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
            "time.sleep(60)\n",
            encoding="utf-8",
        )
        parent = directory / "parent.py"
        parent.write_text(
            "import os, subprocess, sys, time\n"
            "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
            "child = subprocess.Popen([sys.executable, sys.argv[2], sys.argv[3]])\n"
            "open(sys.argv[4], 'w').write(str(child.pid))\n"
            "time.sleep(60)\n",
            encoding="utf-8",
        )
        return parent, grandchild

    @staticmethod
    def _write_exited_parent_scripts(directory: Path) -> tuple[Path, Path]:
        """A leader that exits immediately while its child keeps the inherited pipes open."""
        grandchild = directory / "orphan_grandchild.py"
        grandchild.write_text(
            "import os, sys, time\n"
            "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
            "time.sleep(60)\n",
            encoding="utf-8",
        )
        parent = directory / "exiting_parent.py"
        parent.write_text(
            "import os, subprocess, sys, time\n"
            "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
            "open(sys.argv[2], 'w').write(str(os.getpgrp()))\n"
            "subprocess.Popen([sys.executable, sys.argv[3], sys.argv[4]])\n"
            "for _ in range(250):\n"
            "    if os.path.exists(sys.argv[4]):\n"
            "        break\n"
            "    time.sleep(0.02)\n",
            encoding="utf-8",
        )
        return parent, grandchild

    @staticmethod
    def _pid_alive(pid: int) -> bool:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        return True

    def test_normal_and_failed_commands_return_their_real_exit_status(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ok = browser_verifier.run_command(
                [sys.executable, "-c", "print('owned ok')"],
                cwd=root, environment=os.environ, timeout=10, operation="normal command",
            )
            self.assertEqual(ok.returncode, 0)
            self.assertIn("owned ok", ok.stdout)
            failed = browser_verifier.run_command(
                [sys.executable, "-c", "import sys; sys.stderr.write('diagnostic'); sys.exit(3)"],
                cwd=root, environment=os.environ, timeout=10, operation="failed command",
            )
            self.assertEqual(failed.returncode, 3)
            self.assertIn("diagnostic", failed.stderr)

    def test_rejected_timeout_never_starts_the_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "started.marker"
            with self.assertRaises(browser_verifier.VerificationTimeout):
                browser_verifier.run_command(
                    [sys.executable, "-c",
                     "import pathlib, sys, time; pathlib.Path(sys.argv[1]).write_text('started'); time.sleep(60)",
                     str(marker)],
                    cwd=root, environment=os.environ, timeout=0, operation="invalid timeout",
                )
            self.assertFalse(marker.exists())

    def test_timeout_kills_only_the_owned_process_tree_and_hides_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent, grandchild = self._write_scripts(root)
            parent_marker = root / "parent.pid"
            child_marker = root / "grandchild.pid"
            child_pid_marker = root / "child.pid"
            environment = {**os.environ, "P6_REGRESSION_SECRET": SECRET}
            with self.assertRaises(browser_verifier.VerificationTimeout) as caught:
                browser_verifier.run_command(
                    [sys.executable, str(parent), str(parent_marker), str(grandchild),
                     str(child_marker), str(child_pid_marker)],
                    cwd=root, environment=environment, timeout=3.0, operation="owned tree regression",
                )
            self.assertNotIn(SECRET, str(caught.exception))
            self.assertTrue(parent_marker.exists())
            self.assertTrue(child_pid_marker.exists())
            self.assertTrue(child_marker.exists(), "the grandchild must have started before the timeout")
            parent_pid = int(parent_marker.read_text(encoding="utf-8"))
            child_pid = int(child_marker.read_text(encoding="utf-8"))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and (self._pid_alive(parent_pid) or self._pid_alive(child_pid)):
                time.sleep(0.05)
            self.assertFalse(self._pid_alive(parent_pid), "the direct child must be reaped")
            self.assertFalse(self._pid_alive(child_pid), "the owned grandchild must not survive")

    def test_exited_parent_timeout_kills_the_owned_grandchild(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent, grandchild = self._write_exited_parent_scripts(root)
            parent_marker = root / "exited-parent.pid"
            pgid_marker = root / "owned.pgid"
            grandchild_marker = root / "orphan-grandchild.pid"
            pgid = 0
            grandchild_pid = 0
            started = time.monotonic()
            try:
                with self.assertRaises(browser_verifier.VerificationTimeout):
                    browser_verifier.run_command(
                        [sys.executable, str(parent), str(parent_marker), str(pgid_marker),
                         str(grandchild), str(grandchild_marker)],
                        cwd=root, environment=os.environ, timeout=1.0,
                        operation="exited parent regression",
                    )
                elapsed = time.monotonic() - started
                self.assertTrue(parent_marker.exists(), "the parent must have started")
                self.assertTrue(pgid_marker.exists(), "the owned process group must be recorded")
                self.assertTrue(grandchild_marker.exists(), "the grandchild must have started")
                pgid = int(pgid_marker.read_text(encoding="utf-8"))
                grandchild_pid = int(grandchild_marker.read_text(encoding="utf-8"))
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline and self._pid_alive(grandchild_pid):
                    time.sleep(0.05)
                self.assertFalse(
                    self._pid_alive(grandchild_pid),
                    "a timeout must kill the owned group even after its leader was reaped",
                )
                self.assertLess(elapsed, 5.0, "the owned tree must be drained and reaped in bounded time")
            finally:
                # Safety net for the exact group this test created, never a global discovery.
                if pgid > 0:
                    try:
                        os.killpg(pgid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError, OSError):
                        pass
                    deadline = time.monotonic() + 3
                    while time.monotonic() < deadline and grandchild_pid and self._pid_alive(grandchild_pid):
                        time.sleep(0.05)


class PrivacyTests(unittest.TestCase):
    def test_temp_root_uses_only_an_explicit_override_or_the_generic_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            configured = Path(directory) / "explicit"
            with mock.patch.dict(os.environ, {"P6_TMP_ROOT": str(configured)}, clear=False):
                self.assertEqual(browser_verifier._temp_root(), configured)
                self.assertTrue(configured.is_dir())
            with mock.patch.dict(os.environ, {}, clear=True), \
                    mock.patch.object(browser_verifier.tempfile, "gettempdir", return_value=directory):
                self.assertEqual(browser_verifier._temp_root(), Path(directory))


class PlaywrightSummaryTests(unittest.TestCase):
    def test_summary_counts_passed_failed_and_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            path.write_text(json.dumps({
                "suites": [{
                    "specs": [
                        {"title": "one", "tests": [{"results": [{"status": "passed", "duration": 10}]}]},
                        {"title": "two", "tests": [{"results": [{"status": "failed", "duration": 20}]}]},
                        {"title": "three", "tests": [{"results": [{"status": "skipped", "duration": 0}]}]},
                    ],
                }],
                "errors": [{"message": "sanitized error"}],
            }), encoding="utf-8")
            summary = browser_verifier._playwright_summary(path)
            self.assertEqual(summary["counts"], {"passed": 1, "failed": 1, "skipped": 1})
            self.assertEqual([test["title"] for test in summary["tests"]], ["one", "two", "three"])
            self.assertFalse(summary["malformed"])

    def test_missing_and_malformed_result_files_are_marked_unavailable(self) -> None:
        missing = browser_verifier._playwright_summary(Path("/nonexistent/p6/result.json"))
        self.assertFalse(missing["available"])
        self.assertTrue(missing["malformed"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            path.write_text("not json", encoding="utf-8")
            malformed = browser_verifier._playwright_summary(path)
            self.assertTrue(malformed["malformed"])


if __name__ == "__main__":
    unittest.main()
