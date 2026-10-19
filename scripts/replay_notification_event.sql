-- Privileged, company/event-scoped maintenance replay for one stable notification event.
--
-- Run this only while the notification worker is stopped. This is an explicit operator action, not
-- an application command or an HTTP endpoint. It never touches business, inbox, delivery, in-app or
-- email-job rows, and it refuses a dispatch row that is missing, belongs to another company, is not
-- PUBLISHED/QUARANTINED, or is still leased.
--
-- The selected immutable outbox event is the only authority. The relay publishes exactly
-- envelope::text, so the canonical event's UTF-8 bytes can re-derive the raw transient fingerprint
-- that the consumer records when its canonical snapshot read fails. The raw record therefore keeps a
-- NULL company/event scope (the schema cannot reference an event scope in that path) but is matched
-- by the exact hash, fixed channel and fixed error code. Malformed or unknown bodies still cannot be
-- reconstructed from their hash; that documented limitation remains.
--
-- Replace the symbolic placeholders before execution. PostgreSQL core sha256(), encode() and
-- convert_to() are sufficient; no extension is required.

BEGIN;

DO $replay$
DECLARE
    locked integer;
    canonical_body bytea;
    raw_transient_hash text;
BEGIN
    SELECT 1 INTO locked
        FROM public.outbox_dispatch
        WHERE company_id = '<company-id>'::uuid
          AND event_id = '<event-id>'::uuid
          AND state IN ('PUBLISHED', 'QUARANTINED')
          AND (lease_until IS NULL OR lease_until < now())
        FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'replay refused: dispatch row missing, not published/quarantined or live-leased';
    END IF;

    SELECT convert_to(envelope::text, 'UTF8') INTO canonical_body
        FROM public.outbox_event
        WHERE company_id = '<company-id>'::uuid AND id = '<event-id>'::uuid;
    IF canonical_body IS NULL THEN
        RAISE EXCEPTION 'replay refused: canonical event missing for the scoped company/event';
    END IF;

    -- ConsumerFailureFingerprint.forRawBody(body, MATERIALIZATION_FAILED):
    -- sha256('p4c:v1:raw:materialization_failed:' || lower(hex(sha256(body)))).
    raw_transient_hash := encode(sha256(convert_to(
        'p4c:v1:raw:materialization_failed:' || encode(sha256(canonical_body), 'hex'), 'UTF8')), 'hex');

    DELETE FROM public.notification_diagnostic
        WHERE company_id = '<company-id>'::uuid AND event_id = '<event-id>'::uuid;

    -- Only the exact canonical raw transient record may be reset. A broad NULL-scope delete would
    -- erase unrelated malformed or unknown-input diagnostics and is deliberately not used.
    DELETE FROM public.notification_diagnostic
        WHERE payload_hash = raw_transient_hash
          AND company_id IS NULL
          AND event_id IS NULL
          AND channel = 'IN_APP'
          AND error_code = 'materialization_failed';

    UPDATE public.outbox_dispatch
        SET state = 'PENDING', attempts = 0, next_attempt_at = now(), lease_token = NULL,
            lease_until = NULL, published_at = NULL, error_code = NULL
        WHERE company_id = '<company-id>'::uuid AND event_id = '<event-id>'::uuid;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'replay update touched no dispatch row';
    END IF;
END;
$replay$;

COMMIT;
