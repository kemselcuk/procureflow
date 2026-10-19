"""Focused regression tests for the notification verifier's bounded waits.

Standard library only; no container, network or database is contacted. A deterministic
fake clock and mocked fixture callbacks isolate the verifier control flow (deadline
origin, late-success rejection, retry classification and secret redaction); they are
not evidence of real product or service availability.
"""

import unittest
from unittest import mock

import notification_smoke


class FakeClock:
    """Deterministic monotonic clock; sleeping advances the fake time."""

    def __init__(self, now=0.0):
        self.now = now
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def advance(self, seconds):
        self.now += seconds


class ClockPatcher:
    """Patch the verifier's clock lookup at call time; nothing else is mocked."""

    def __init__(self, clock):
        self.clock = clock
        self._patches = []

    def __enter__(self):
        self._patches = [
            mock.patch.object(notification_smoke.time, "monotonic", self.clock.monotonic),
            mock.patch.object(notification_smoke.time, "sleep", self.clock.sleep),
        ]
        for patch in self._patches:
            patch.start()
        return self.clock

    def __exit__(self, *exc_info):
        for patch in reversed(self._patches):
            patch.stop()
        return False


def observation_seconds(result):
    """Accept the pre-fix float return so the deadline tests stay red on R2."""
    return result.seconds if hasattr(result, "seconds") else round(result, 3)


def make_fixture(snapshot, problems=None):
    """Minimal Fixture with mocked snapshot/completion_problems; no stack is built."""
    fixture = notification_smoke.Fixture(stack=None, tokens={})
    fixture.snapshot = snapshot
    pending = list(problems or [])
    fixture.completion_problems = lambda snapshot_value, expected, event_ids: list(pending)
    return fixture


class WaitForDeadlineTests(unittest.TestCase):
    EVENT_IDS = ["00000000-0000-0000-0000-000000000000"]

    def test_late_success_is_rejected(self):
        clock = FakeClock()

        def predicate():
            clock.advance(61)
            return {"ok": True}

        with ClockPatcher(clock):
            with self.assertRaises(RuntimeError) as captured:
                notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertIn("late success", str(captured.exception).lower())
        self.assertIn("61", str(captured.exception))

    def test_timely_success_is_returned(self):
        clock = FakeClock()

        def predicate():
            clock.advance(25)
            return {"ok": True}

        with ClockPatcher(clock):
            result = notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertEqual(result, {"ok": True})
        self.assertEqual(clock.sleeps, [])

    def test_exact_boundary_success_is_allowed(self):
        clock = FakeClock()

        def predicate():
            clock.advance(60)
            return {"ok": True}

        with ClockPatcher(clock):
            result = notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertEqual(result, {"ok": True})

    def test_false_until_deadline_fails(self):
        clock = FakeClock()
        calls = []

        def predicate():
            calls.append(clock.now)
            return None

        with ClockPatcher(clock):
            with self.assertRaises(RuntimeError) as captured:
                notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertIn("Timed out after 60s", str(captured.exception))
        self.assertEqual(calls[-1], 60)
        self.assertTrue(all(moment <= 60 for moment in calls))

    def test_transient_failure_then_success_is_not_treated_as_success(self):
        clock = FakeClock()
        attempts = []

        def predicate():
            attempts.append(clock.now)
            if len(attempts) == 1:
                clock.advance(2)
                raise notification_smoke.TransientWaitError("transient probe")
            clock.advance(3)
            return "ready"

        with ClockPatcher(clock):
            result = notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertEqual(result, "ready")
        self.assertEqual(attempts, [0, 3])

    def test_late_success_does_not_leak_observation_contents(self):
        sentinel = "sentinel-observation-body-91bc"
        clock = FakeClock()

        def predicate():
            clock.advance(61)
            return {"payload": sentinel}

        with ClockPatcher(clock):
            with self.assertRaises(RuntimeError) as captured:
                notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertNotIn(sentinel, str(captured.exception))

    def test_persistent_transient_failure_does_not_leak_error_body(self):
        sentinel = "sentinel-transient-body-2f7a"

        def predicate():
            raise notification_smoke.TransientWaitError(sentinel)

        with ClockPatcher(FakeClock()):
            with self.assertRaises(RuntimeError) as captured:
                notification_smoke.wait_for("fake observation", predicate, 2)
        message = str(captured.exception)
        self.assertNotIn(sentinel, message)
        self.assertIn("TransientWaitError", message)

    def test_invariant_failure_propagates_immediately(self):
        clock = FakeClock()

        def predicate():
            raise RuntimeError("invariant sentinel")

        with ClockPatcher(clock):
            with self.assertRaises(RuntimeError) as captured:
                notification_smoke.wait_for("fake observation", predicate, 60)
        self.assertEqual(str(captured.exception), "invariant sentinel")
        self.assertEqual(clock.sleeps, [])


class WaitCompletedDeadlineTests(unittest.TestCase):
    EVENT_IDS = ["00000000-0000-0000-0000-000000000000"]

    def wait_completed(self, fixture, clock, *, timeout=60, started_at=None):
        with ClockPatcher(clock):
            return fixture.wait_completed(self.EVENT_IDS, {"e": {}}, timeout, started_at)

    def test_late_success_is_rejected(self):
        clock = FakeClock()

        def snapshot(event_ids):
            clock.advance(61)
            return {"observed": True}

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot), clock)
        self.assertIn("after the 60s bound", str(captured.exception))
        self.assertIn("61", str(captured.exception))

    def test_timely_success_returns_observation(self):
        clock = FakeClock()

        def snapshot(event_ids):
            clock.advance(25)
            return {"observed": True}

        result = self.wait_completed(make_fixture(snapshot), clock)
        self.assertEqual(observation_seconds(result), 25.0)

    def test_exact_boundary_success_is_allowed(self):
        clock = FakeClock()

        def snapshot(event_ids):
            clock.advance(60)
            return {"observed": True}

        result = self.wait_completed(make_fixture(snapshot), clock)
        self.assertEqual(observation_seconds(result), 60.0)

    def test_false_until_deadline_fails(self):
        clock = FakeClock()
        calls = []

        def snapshot(event_ids):
            calls.append(clock.now)
            return {"observed": False}

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot, problems=["still pending"]), clock)
        self.assertIn("fixture completion exceeded 60s", str(captured.exception))
        self.assertEqual(calls[-1], 60)
        self.assertTrue(all(moment <= 60 for moment in calls))

    def test_transient_failure_then_success_retries(self):
        clock = FakeClock()
        attempts = []

        def snapshot(event_ids):
            attempts.append(clock.now)
            if len(attempts) == 1:
                clock.advance(2)
                raise notification_smoke.TransientWaitError("transient psql read")
            clock.advance(3)
            return {"observed": True}

        result = self.wait_completed(make_fixture(snapshot), clock)
        self.assertEqual(observation_seconds(result), 6.0)
        self.assertEqual(attempts, [0, 3])
        self.assertEqual(clock.sleeps, [1.0])

    def test_persistent_transient_failure_does_not_leak_error_body(self):
        sentinel = "sentinel-transient-body-2f7a"

        def snapshot(event_ids):
            raise notification_smoke.TransientWaitError(sentinel)

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot), FakeClock(), timeout=2)
        message = str(captured.exception)
        self.assertNotIn(sentinel, message)
        self.assertIn("TransientWaitError", message)

    def test_invariant_failure_propagates_immediately(self):
        clock = FakeClock()

        def snapshot(event_ids):
            raise RuntimeError("invariant sentinel")

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot), clock)
        self.assertEqual(str(captured.exception), "invariant sentinel")
        self.assertEqual(clock.sleeps, [])

    def test_supplied_started_at_does_not_reset_the_budget(self):
        clock = FakeClock(now=100.0)

        def snapshot(event_ids):
            return {"observed": True}

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot), clock, started_at=0.0)
        self.assertIn("after the 60s bound", str(captured.exception))

    def test_supplied_started_at_boundary_is_measured_from_the_origin(self):
        clock = FakeClock(now=59.5)
        state = {"done": False}

        def snapshot(event_ids):
            if not state["done"]:
                clock.advance(0.5)
                state["done"] = True
            return {"observed": True}

        result = self.wait_completed(make_fixture(snapshot), clock, started_at=0.0)
        self.assertEqual(observation_seconds(result), 60.0)

    def test_supplied_started_at_rejects_observation_past_the_origin_bound(self):
        clock = FakeClock(now=59.5)

        def snapshot(event_ids):
            clock.advance(0.6)
            return {"observed": True}

        with self.assertRaises(RuntimeError) as captured:
            self.wait_completed(make_fixture(snapshot), clock, started_at=0.0)
        self.assertIn("after the 60s bound", str(captured.exception))


class RedactionRegressionTests(unittest.TestCase):
    """R2 temporary-probe sentinel: real failure paths must stay credential-safe."""

    def test_sanitize_text_redacts_sentinel_and_credentials(self):
        sentinel = "sentinel-password-7c1f"
        text = ("ERROR: PGPASSWORD=" + sentinel + " "
                "Authorization: Basic QWxhZGRpbjpvcGVuc2VzYW1l "
                "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl")
        redacted = notification_smoke.sanitize_text(text, [sentinel])
        self.assertNotIn(sentinel, redacted)
        self.assertNotIn("QWxhZGRpbjpvcGVuc2VzYW1l", redacted)
        self.assertNotIn("eyJhbGciOiJIUzI1NiJ9", redacted)
        self.assertIn("[REDACTED]", redacted)

    def test_sql_error_summary_redacts_generated_secret(self):
        secret = "generated-pg-password-4f2a"
        stack = notification_smoke.NotificationStack(
            root=".", compose_argv=[], environment={"POSTGRES_PASSWORD": secret}, project="fixture",
            frontend="", provider="", service_address=lambda *args: "", base_url=lambda *args: "",
            rabbitmq_request=lambda path: None, mailpit_api=lambda path: None, log=lambda *args: None)
        summary = stack._sql_error_summary(
            "psql: ERROR: PGPASSWORD=" + secret + " password authentication failed\n"
            "DETAIL: " + secret)
        self.assertNotIn(secret, summary)
        self.assertIn("ERROR", summary)


if __name__ == "__main__":
    unittest.main()
