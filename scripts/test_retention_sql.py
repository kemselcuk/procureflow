"""Bounded tests for scripts/retention_notification_diagnostic.sql.

The static tests run everywhere and assert the script's write scope. The integration test uses a
disposable PostgreSQL container, copies the V5 diagnostic DDL, then runs the real script through
the real psql variable/preview/apply path. It proves old raw rows are deleted in bounded batches
while recent raw, replay-fingerprint and event-scoped terminal rows survive.

Standard library only. No production database, network service, credential or repository fixture
is contacted; the container is unique, unexposed and removed in tearDownClass.
"""

import pathlib
import re
import shutil
import subprocess
import time
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "retention_notification_diagnostic.sql"
POM = ROOT / "pom.xml"


def script_text():
    return SCRIPT.read_text(encoding="utf-8")


def postgres_image():
    """Reuse the pinned Testcontainers image from the root Maven properties."""
    match = re.search(r"<postgres\.image>([^<]+)</postgres\.image>", POM.read_text(encoding="utf-8"))
    if not match:
        raise AssertionError("postgres.image is missing from pom.xml")
    return match.group(1).strip()


def diagnostic_schema():
    """Minimal FK target plus the notification_diagnostic DDL copied verbatim from V5."""
    return """
        CREATE TABLE outbox_event (
            id UUID PRIMARY KEY,
            company_id UUID NOT NULL,
            UNIQUE (company_id, id)
        );
    """ + DIAGNOSTIC_DDL


# Kept byte-for-byte aligned with V5 (modulo leading indentation); the static test below compares
# these normalized lines with the migration block so a silent schema drift fails the suite.
DIAGNOSTIC_DDL = """
CREATE TABLE notification_diagnostic (
    payload_hash CHAR(64) PRIMARY KEY,
    company_id UUID,
    event_id UUID,
    channel VARCHAR(8) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_diagnostic_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_notification_diagnostic_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notification_diagnostic_error CHECK (error_code ~ '^[a-z0-9_]{1,64}$'),
    CONSTRAINT ck_notification_diagnostic_attempts CHECK (attempts > 0),
    CONSTRAINT ck_notification_diagnostic_scope CHECK ((company_id IS NULL) = (event_id IS NULL)),
    CONSTRAINT ck_notification_diagnostic_seen CHECK (first_seen_at <= last_seen_at),
    CONSTRAINT fk_notification_diagnostic_event FOREIGN KEY (company_id, event_id)
        REFERENCES outbox_event(company_id, id)
);
"""


def v5_diagnostic_ddl():
    text = (ROOT / "backend/app/src/main/resources/db/migration/V5__notification_delivery_foundation.sql").read_text(
        encoding="utf-8")
    start = text.index("CREATE TABLE notification_diagnostic (")
    end = text.index("\n);", start) + len("\n);")
    return text[start:end]


def normalized_lines(sql):
    return [line.strip() for line in sql.strip().splitlines() if line.strip()]


class RetentionSqlTextTests(unittest.TestCase):
    """Static, database-free checks for the script's bounded raw-only write scope."""

    def test_script_exists_and_requires_all_psql_variables(self):
        text = script_text()
        for variable in ("retention_days", "batch_size", "preview"):
            self.assertIn(":{?%s}" % variable, text)
            self.assertIn("requires -v %s" % variable, text)

    def test_only_notification_diagnostic_is_deleted(self):
        text = script_text()
        deletes = re.findall(r"DELETE\s+FROM\s+([a-z_.]+)", text, flags=re.IGNORECASE)
        self.assertEqual(["notification_diagnostic"], deletes)
        # No other data-owning table may appear in a modifying statement.
        for forbidden in ("audit_entry", "request_history", "outbox_event", "outbox_recipient",
                          "outbox_dispatch", "notification_inbox", "notification_delivery",
                          "in_app_notification", "email_job", "api_command"):
            self.assertNotRegex(text, r"(?i)(INSERT\s+INTO|UPDATE|TRUNCATE)\s+" + forbidden)

    def test_delete_scope_is_raw_old_and_not_a_replay_fingerprint(self):
        text = script_text()
        self.assertIn("d.company_id IS NULL", text)
        self.assertIn("d.event_id IS NULL", text)
        self.assertIn("d.last_seen_at < now() - make_interval", text)
        self.assertIn("d.error_code <> 'materialization_failed'", text)
        self.assertIn("LIMIT :batch_size", text)
        self.assertIn("FOR UPDATE SKIP LOCKED", text)
        self.assertIn("BEGIN;", text)
        self.assertIn("COMMIT;", text)

    def test_copied_diagnostic_ddl_matches_v5(self):
        self.assertEqual(normalized_lines(DIAGNOSTIC_DDL), normalized_lines(v5_diagnostic_ddl()))

    def test_preview_is_validated_before_the_apply_branch(self):
        text = script_text()
        self.assertIn("preview must be true or false", text)
        self.assertIn("preview_mode", text)
        self.assertIn("\\if :preview_mode", text)
        self.assertNotIn("\\if :preview\n", text)


class RetentionSqlIntegrationTests(unittest.TestCase):
    """Real PostgreSQL through real psql; skipped only when Docker is unavailable."""

    @classmethod
    def setUpClass(cls):
        if shutil.which("docker") is None:
            raise unittest.SkipTest("docker CLI is unavailable; retention SQL integration skipped")
        cls.container = "procureflow-retention-" + uuid.uuid4().hex[:16]
        cls.password = uuid.uuid4().hex
        cls.image = postgres_image()
        run = subprocess.run(
            ["docker", "run", "-d", "--rm", "--name", cls.container,
             "-e", "POSTGRES_PASSWORD=" + cls.password, "-e", "POSTGRES_DB=retention_test",
             cls.image],
            capture_output=True, text=True, timeout=120)
        if run.returncode != 0:
            raise unittest.SkipTest("cannot start disposable PostgreSQL: " + run.stderr.strip()[:200])
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            ready = subprocess.run(
                ["docker", "exec", cls.container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres",
                 "-d", "retention_test", "-c", "SELECT 1"],
                capture_output=True, text=True, timeout=30)
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            cls.tearDownClass()
            raise AssertionError("disposable PostgreSQL did not become ready within 90s")
        try:
            cls.psql(diagnostic_schema())
        except Exception:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "container", None):
            subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True, text=True, timeout=60)

    @classmethod
    def psql(cls, sql):
        result = subprocess.run(
            ["docker", "exec", "-i", cls.container, "psql", "-v", "ON_ERROR_STOP=1",
             "-U", "postgres", "-d", "retention_test", "-At", "-F", "|"],
            input=sql, capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            raise AssertionError("psql failed: " + (result.stderr or result.stdout))
        return result.stdout.strip()

    def setUp(self):
        self.psql("TRUNCATE notification_diagnostic, outbox_event CASCADE;")

    def seed(self):
        company = uuid.uuid4()
        event = uuid.uuid4()
        self.psql("INSERT INTO outbox_event (id, company_id) VALUES ('%s', '%s');" % (event, company))
        rows = []
        # Five old raw malformed rows: all eligible, used to prove the batch bound.
        for index in range(1, 6):
            rows.append(("%064x" % index, "malformed_message", None, None,
                         "now() - interval '100 days'"))
        # One more old raw non-replay row.
        rows.append(("b" * 64, "transport_identity_mismatch", None, None,
                     "now() - interval '200 days'"))
        # Recent raw row: never eligible by age.
        rows.append(("c" * 64, "malformed_message", None, None, "now() - interval '2 days'"))
        # Old raw replay fingerprint: never deleted by this script.
        rows.append(("d" * 64, "materialization_failed", None, None,
                     "now() - interval '300 days'"))
        # Old event-scoped terminal rows: never deleted, whatever the code/age.
        rows.append(("e" * 64, "materialization_failed", company, event,
                     "now() - interval '300 days'"))
        rows.append(("f" * 64, "malformed_message", company, event,
                     "now() - interval '300 days'"))
        for payload_hash, code, company_id, event_id, seen in rows:
            scope = "NULL, NULL" if company_id is None else "'%s', '%s'" % (company_id, event_id)
            self.psql(
                "INSERT INTO notification_diagnostic "
                "(payload_hash, company_id, event_id, channel, error_code, attempts, first_seen_at, last_seen_at) "
                "VALUES ('%s', %s, 'IN_APP', '%s', 5, %s, %s);"
                % (payload_hash, scope, code, seen, seen))
        return company, event

    def run_script(self, days=30, batch=2, preview=False):
        return self.run_script_values({
            "retention_days": days,
            "batch_size": batch,
            "preview": "true" if preview else "false",
        })

    def run_script_values(self, values):
        args = ["docker", "exec", "-i", self.container, "psql", "-U", "postgres", "-d", "retention_test",
                "-f", "-"]
        for key, value in values.items():
            args += ["-v", "%s=%s" % (key, value)]
        return subprocess.run(args, input=script_text(), capture_output=True, text=True, timeout=90)

    def counts(self):
        output = self.psql(
            "SELECT count(*), count(*) FILTER (WHERE company_id IS NULL AND event_id IS NULL), "
            "count(*) FILTER (WHERE company_id IS NULL AND error_code = 'materialization_failed'), "
            "count(*) FILTER (WHERE company_id IS NOT NULL) FROM notification_diagnostic;")
        return [int(value) for value in output.split("|")]

    def test_preview_is_read_only_and_apply_is_bounded_and_scoped(self):
        self.seed()
        before = self.counts()
        self.assertEqual([10, 8, 1, 2], before)

        preview = self.run_script(preview=True)
        self.assertEqual(0, preview.returncode, preview.stderr or preview.stdout)
        self.assertIn("PREVIEW ONLY", preview.stdout)
        self.assertEqual(before, self.counts(), "preview must not delete rows")

        previous = before[0]
        for expected_deleted in (2, 2, 2, 0):
            applied = self.run_script(preview=False)
            self.assertEqual(0, applied.returncode, applied.stderr or applied.stdout)
            current = self.counts()[0]
            self.assertEqual(expected_deleted, previous - current, "bounded apply batch")
            previous = current
        self.assertEqual([4, 2, 1, 2], self.counts(),
                         "only old raw non-replay rows may be deleted")

    def test_missing_variables_fail_without_deleting(self):
        self.seed()
        before = self.counts()
        for omitted in ("retention_days", "batch_size", "preview"):
            supplied = {"retention_days": "30", "batch_size": "2", "preview": "false"}
            del supplied[omitted]
            result = self.run_script_values(supplied)
            self.assertNotEqual(0, result.returncode, omitted)
            self.assertIn("missing required variable", result.stdout + result.stderr)
            self.assertEqual(before, self.counts(), "a missing variable must not delete rows")

    def test_out_of_range_and_non_numeric_parameters_fail_without_deleting(self):
        self.seed()
        before = self.counts()
        cases = [
            {"retention_days": "0", "batch_size": "2", "preview": "false"},
            {"retention_days": "36501", "batch_size": "2", "preview": "false"},
            {"retention_days": "abc", "batch_size": "2", "preview": "false"},
            {"retention_days": "30", "batch_size": "0", "preview": "false"},
            {"retention_days": "30", "batch_size": "10001", "preview": "false"},
            {"retention_days": "30", "batch_size": "abc", "preview": "false"},
        ]
        for values in cases:
            result = self.run_script_values(values)
            self.assertNotEqual(0, result.returncode, values)
            self.assertEqual(before, self.counts(), values)

    def test_invalid_preview_fails_without_deleting_but_case_is_insensitive(self):
        self.seed()
        before = self.counts()
        invalid = self.run_script_values(
            {"retention_days": "30", "batch_size": "2", "preview": "maybe"})
        self.assertNotEqual(0, invalid.returncode)
        self.assertIn("preview must be true or false", invalid.stdout + invalid.stderr)
        self.assertEqual(before, self.counts(), "an invalid preview must not delete rows")

        preview = self.run_script_values(
            {"retention_days": "30", "batch_size": "2", "preview": "TRUE"})
        self.assertEqual(0, preview.returncode, preview.stderr or preview.stdout)
        self.assertIn("PREVIEW ONLY", preview.stdout)
        self.assertEqual(before, self.counts(), "uppercase TRUE is a read-only preview")


if __name__ == "__main__":
    unittest.main()
