-- Operator-run retention for old raw-scoped notification diagnostics.
--
-- This is an explicit maintenance action, not an application command, scheduled job or worker
-- capability. The application/worker migrations grant no DELETE on this table; run the script as
-- the schema owner/migrator (for example `psql "$MIGRATOR_URL" ...`).
--
-- What is eligible:
--   * rows with BOTH company_id and event_id NULL (raw transport/parse/transient fingerprints),
--   * last_seen_at older than the retention window,
--   * any error_code EXCEPT materialization_failed.
-- `materialization_failed` raw fingerprints are the exact raw evidence that
-- scripts/replay_notification_event.sql re-derives and resets, so they are retained here and must
-- be handled by the replay procedure instead of age-based deletion.
--
-- What is NEVER touched:
--   * event-scoped rows (company_id/event_id NOT NULL), including terminal/permanent quarantine
--     codes and exhausted transient records, regardless of age;
--   * audit_entry, request_history, outbox_event, outbox_recipient, outbox_dispatch,
--     notification_inbox, notification_delivery, in_app_notification, email_job and api_command.
--
-- The script is bounded: one transaction removes at most :batch_size rows, so repeated runs are
-- required for a large backlog. Preview first, review the printed counts, then apply. A missing
-- variable, a non-numeric/invalid value or a failed statement stops psql before deletion; the
-- transaction rolls back on error.
--
-- Usage:
--   Preview (read-only, prints eligible/retained counts and deletes nothing):
--     psql "$MIGRATOR_URL" -v retention_days=90 -v batch_size=1000 -v preview=true \
--          -f scripts/retention_notification_diagnostic.sql
--   Apply one bounded batch:
--     psql "$MIGRATOR_URL" -v retention_days=90 -v batch_size=1000 -v preview=false \
--          -f scripts/retention_notification_diagnostic.sql
--   Repeat the apply command until it reports `deleted = 0`, reviewing each run.

\set ON_ERROR_STOP on

\if :{?retention_days}
\else
    \echo 'ERROR: missing required variable -v retention_days=<whole days, 1..36500>'
    DO $retention_abort$ BEGIN RAISE EXCEPTION 'retention_notification_diagnostic.sql requires -v retention_days'; END $retention_abort$;
\endif
\if :{?batch_size}
\else
    \echo 'ERROR: missing required variable -v batch_size=<whole rows, 1..10000>'
    DO $retention_abort$ BEGIN RAISE EXCEPTION 'retention_notification_diagnostic.sql requires -v batch_size'; END $retention_abort$;
\endif
\if :{?preview}
\else
    \echo 'ERROR: missing required variable -v preview=<true|false>'
    DO $retention_abort$ BEGIN RAISE EXCEPTION 'retention_notification_diagnostic.sql requires -v preview'; END $retention_abort$;
\endif

-- Validate/normalize the inputs once. Non-numeric text fails the cast; out-of-range numbers and an
-- invalid preview value raise before any delete. The temporary table is session-local and contains
-- no app data.
CREATE TEMP TABLE retention_parameters AS
SELECT :'retention_days'::bigint AS retention_days,
       :'batch_size'::bigint AS batch_size,
       :'preview' AS preview;

DO $retention_guard$
DECLARE
    parameters retention_parameters%ROWTYPE;
BEGIN
    SELECT * INTO parameters FROM retention_parameters;
    IF parameters.retention_days < 1 OR parameters.retention_days > 36500 THEN
        RAISE EXCEPTION 'retention_days must be between 1 and 36500, got %',
            parameters.retention_days;
    END IF;
    IF parameters.batch_size < 1 OR parameters.batch_size > 10000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 10000, got %', parameters.batch_size;
    END IF;
    IF lower(btrim(parameters.preview)) NOT IN ('true', 'false') THEN
        RAISE EXCEPTION 'preview must be true or false, got %', parameters.preview;
    END IF;
END
$retention_guard$;

-- Normalize the accepted boolean for \if so only a validated value can reach the apply branch.
SELECT CASE WHEN lower(btrim(preview)) = 'true' THEN 'true' ELSE 'false' END AS preview_mode
FROM retention_parameters \gset

\echo ''
\echo 'notification_diagnostic retention'
\echo '  retention_days  =' :retention_days '(rows older than now() - this interval)'
\echo '  batch_size      =' :batch_size '(maximum rows in one apply transaction)'
\echo '  eligible scope  = raw only (company_id IS NULL AND event_id IS NULL), error_code <> materialization_failed'
\echo '  never touched   = event-scoped terminal/quarantine rows, replay fingerprints, business/delivery tables'
\echo ''

SELECT
    (SELECT count(*) FROM notification_diagnostic d
     WHERE d.company_id IS NULL AND d.event_id IS NULL
       AND d.error_code <> 'materialization_failed'
       AND d.last_seen_at < now() - make_interval(days => :retention_days)) AS eligible_raw_rows,
    (SELECT count(*) FROM notification_diagnostic d
     WHERE d.company_id IS NULL AND d.event_id IS NULL
       AND d.error_code = 'materialization_failed'
       AND d.last_seen_at < now() - make_interval(days => :retention_days)) AS retained_replay_fingerprints,
    (SELECT count(*) FROM notification_diagnostic d WHERE d.company_id IS NOT NULL)
        AS event_scoped_rows_never_touched;

SELECT d.error_code, count(*) AS eligible_rows,
       min(d.last_seen_at) AS oldest_last_seen, max(d.last_seen_at) AS newest_last_seen
FROM notification_diagnostic d
WHERE d.company_id IS NULL AND d.event_id IS NULL
  AND d.error_code <> 'materialization_failed'
  AND d.last_seen_at < now() - make_interval(days => :retention_days)
GROUP BY d.error_code
ORDER BY d.error_code;

\if :preview_mode
\echo 'PREVIEW ONLY: no row was deleted. Review the counts above, then re-run with -v preview=false.'
\echo ''
\else
\echo 'APPLY: deleting at most' :batch_size 'eligible raw rows in one transaction.'
BEGIN;
WITH candidates AS (
    SELECT d.payload_hash
    FROM notification_diagnostic d
    WHERE d.company_id IS NULL
      AND d.event_id IS NULL
      AND d.error_code <> 'materialization_failed'
      AND d.last_seen_at < now() - make_interval(days => :retention_days)
    ORDER BY d.last_seen_at, d.payload_hash
    LIMIT :batch_size
    FOR UPDATE SKIP LOCKED
), deleted AS (
    DELETE FROM notification_diagnostic d
    USING candidates c
    WHERE d.payload_hash = c.payload_hash
    RETURNING d.payload_hash
)
SELECT count(*) AS deleted FROM deleted;

SELECT count(*) AS remaining_eligible_raw_rows
FROM notification_diagnostic d
WHERE d.company_id IS NULL AND d.event_id IS NULL
  AND d.error_code <> 'materialization_failed'
  AND d.last_seen_at < now() - make_interval(days => :retention_days);
COMMIT;
\echo 'COMMITTED one bounded batch. Re-run the same apply command while deleted > 0.'
\echo ''
\endif
