-- P4 database foundation: durable dispatch, delivery outcomes and worker-owned jobs.
-- The worker login is provisioned from WORKER_DB_PASSWORD by infra/postgres/init-databases.sh.
-- Existing volumes need the same role provisioned explicitly before this migration can run.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'procureflow_worker' AND rolcanlogin) THEN
        RAISE EXCEPTION 'LOGIN role procureflow_worker is missing; provision it explicitly before applying V5'
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT pg_catalog.has_database_privilege('procureflow_worker', pg_catalog.current_database(), 'CONNECT') THEN
        RAISE EXCEPTION 'Role procureflow_worker cannot connect to the application database; grant CONNECT explicitly before applying V5'
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT pg_catalog.has_schema_privilege('procureflow_worker', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'Role procureflow_worker lacks USAGE on schema public; grant it explicitly before applying V5'
            USING ERRCODE = 'P0001';
    END IF;
END $$;

-- The worker login and schema USAGE are provisioned by infra/postgres/init-databases.sh.

CREATE TABLE outbox_dispatch (
    event_id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    CONSTRAINT fk_outbox_dispatch_event FOREIGN KEY (company_id, event_id)
        REFERENCES outbox_event(company_id, id),
    CONSTRAINT ck_outbox_dispatch_state CHECK (state IN ('PENDING', 'LEASED', 'PUBLISHED', 'QUARANTINED')),
    CONSTRAINT ck_outbox_dispatch_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dispatch_error_code CHECK (error_code IS NULL OR error_code ~ '^[a-z0-9_]{1,64}$'),
    CONSTRAINT ck_outbox_dispatch_state_shape CHECK (
        ((state = 'PUBLISHED') = (published_at IS NOT NULL))
        AND ((state = 'LEASED') = (lease_token IS NOT NULL))
        AND ((state = 'LEASED') = (lease_until IS NOT NULL))
        AND (state <> 'QUARANTINED' OR error_code IS NOT NULL)
        AND (state <> 'PUBLISHED' OR error_code IS NULL)
    )
);
CREATE INDEX ix_outbox_dispatch_due ON outbox_dispatch(next_attempt_at, event_id) WHERE state = 'PENDING';
CREATE INDEX ix_outbox_dispatch_lease ON outbox_dispatch(lease_until, event_id) WHERE state = 'LEASED';
CREATE INDEX ix_outbox_dispatch_company_state ON outbox_dispatch(company_id, state, next_attempt_at);

-- New events get their dispatch row in the same application transaction without granting the app INSERT.
CREATE FUNCTION public.initialize_outbox_dispatch() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO public.outbox_dispatch (event_id, company_id) VALUES (NEW.id, NEW.company_id);
    RETURN NEW;
END $$;
ALTER FUNCTION public.initialize_outbox_dispatch() OWNER TO procureflow_migrator;
REVOKE ALL ON FUNCTION public.initialize_outbox_dispatch() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.initialize_outbox_dispatch() TO procureflow_app;
CREATE TRIGGER initialize_outbox_dispatch AFTER INSERT ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION public.initialize_outbox_dispatch();

-- Existing immutable events become claimable exactly once. The insert is part of Flyway's transaction.
INSERT INTO outbox_dispatch (event_id, company_id)
SELECT id, company_id FROM outbox_event
ON CONFLICT (event_id) DO NOTHING;

-- Enables the exact recipient-snapshot foreign key below without changing recipient cardinality.
ALTER TABLE outbox_recipient ADD CONSTRAINT uq_outbox_recipient_event_identity_membership
    UNIQUE (company_id, event_id, identity_id, membership_id);

CREATE TABLE notification_inbox (
    company_id UUID NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, event_id),
    CONSTRAINT fk_notification_inbox_event FOREIGN KEY (company_id, event_id)
        REFERENCES outbox_event(company_id, id)
);

CREATE TABLE notification_delivery (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    event_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64),
    outcome_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_delivery_event_identity_channel
        UNIQUE (company_id, event_id, identity_id, channel),
    CONSTRAINT uq_notification_delivery_child_binding
        UNIQUE (id, company_id, event_id, identity_id, membership_id, channel, outcome),
    CONSTRAINT fk_notification_delivery_recipient
        FOREIGN KEY (company_id, event_id, identity_id, membership_id)
        REFERENCES outbox_recipient(company_id, event_id, identity_id, membership_id),
    CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notification_delivery_outcome CHECK (outcome IN ('MATERIALIZED', 'SKIPPED')),
    CONSTRAINT ck_notification_delivery_reason CHECK (
        (outcome = 'SKIPPED') = (reason_code IS NOT NULL)
        AND (reason_code IS NULL OR reason_code ~ '^[a-z0-9_]{1,64}$')
    )
);

CREATE TABLE in_app_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL,
    company_id UUID NOT NULL,
    event_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL DEFAULT 'IN_APP',
    delivery_outcome VARCHAR(16) NOT NULL DEFAULT 'MATERIALIZED',
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_in_app_notification_delivery UNIQUE (delivery_id),
    CONSTRAINT fk_in_app_notification_delivery FOREIGN KEY
        (delivery_id, company_id, event_id, identity_id, membership_id, channel, delivery_outcome)
        REFERENCES notification_delivery(id, company_id, event_id, identity_id, membership_id, channel, outcome),
    CONSTRAINT ck_in_app_notification_channel CHECK (channel = 'IN_APP'),
    CONSTRAINT ck_in_app_notification_outcome CHECK (delivery_outcome = 'MATERIALIZED')
);
CREATE INDEX ix_in_app_notification_recipient
    ON in_app_notification(company_id, identity_id, created_at DESC, id DESC);

CREATE TABLE email_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL,
    company_id UUID NOT NULL,
    event_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL DEFAULT 'EMAIL',
    delivery_outcome VARCHAR(16) NOT NULL DEFAULT 'MATERIALIZED',
    recipient_address VARCHAR(320) GENERATED ALWAYS AS (identity_id::text || '@demo.invalid') STORED,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_job_delivery UNIQUE (delivery_id),
    CONSTRAINT fk_email_job_delivery FOREIGN KEY
        (delivery_id, company_id, event_id, identity_id, membership_id, channel, delivery_outcome)
        REFERENCES notification_delivery(id, company_id, event_id, identity_id, membership_id, channel, outcome),
    CONSTRAINT ck_email_job_channel CHECK (channel = 'EMAIL'),
    CONSTRAINT ck_email_job_outcome CHECK (delivery_outcome = 'MATERIALIZED'),
    CONSTRAINT ck_email_job_recipient CHECK (recipient_address = identity_id::text || '@demo.invalid'),
    CONSTRAINT ck_email_job_state CHECK (state IN ('PENDING', 'LEASED', 'SENT', 'QUARANTINED', 'SKIPPED')),
    CONSTRAINT ck_email_job_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_email_job_error_code CHECK (error_code IS NULL OR error_code ~ '^[a-z0-9_]{1,64}$'),
    CONSTRAINT ck_email_job_state_shape CHECK (
        ((state = 'SENT') = (sent_at IS NOT NULL))
        AND ((state = 'LEASED') = (lease_token IS NOT NULL))
        AND ((state = 'LEASED') = (lease_until IS NOT NULL))
        AND (state NOT IN ('QUARANTINED', 'SKIPPED') OR error_code IS NOT NULL)
        AND (state <> 'SENT' OR error_code IS NULL)
    )
);
CREATE INDEX ix_email_job_due ON email_job(next_attempt_at, id) WHERE state = 'PENDING';
CREATE INDEX ix_email_job_lease ON email_job(lease_until, id) WHERE state = 'LEASED';
CREATE INDEX ix_email_job_company_state ON email_job(company_id, state, next_attempt_at);

-- Only a bounded fingerprint and stable code are stored; never a message body, secret or stack trace.
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
CREATE INDEX ix_notification_diagnostic_last_seen ON notification_diagnostic(last_seen_at);

-- Runtime roles never inherit the default app CRUD privileges on delivery-owned tables.
REVOKE ALL ON TABLE outbox_dispatch, notification_inbox, notification_delivery, in_app_notification,
    email_job, notification_diagnostic FROM PUBLIC, procureflow_app, procureflow_worker;

GRANT SELECT ON outbox_dispatch, notification_inbox, notification_delivery, in_app_notification, email_job
    TO procureflow_app;
GRANT UPDATE (read_at) ON in_app_notification TO procureflow_app;

GRANT SELECT (id) ON company TO procureflow_worker;
GRANT SELECT (id, company_id, identity_id, active) ON membership TO procureflow_worker;
GRANT SELECT ON outbox_event, outbox_recipient TO procureflow_worker;

GRANT SELECT, UPDATE (state, attempts, next_attempt_at, lease_token, lease_until, published_at, error_code)
    ON outbox_dispatch TO procureflow_worker;
GRANT SELECT, INSERT ON notification_inbox TO procureflow_worker;
GRANT SELECT, INSERT ON notification_delivery TO procureflow_worker;
GRANT SELECT, INSERT ON in_app_notification TO procureflow_worker;
GRANT SELECT, INSERT, UPDATE (state, attempts, next_attempt_at, lease_token, lease_until, sent_at, error_code)
    ON email_job TO procureflow_worker;
GRANT SELECT, INSERT, UPDATE (attempts, last_seen_at) ON notification_diagnostic TO procureflow_worker;

-- The worker joins the existing company-barrier order: company SHARE, then delivery rows.
CREATE FUNCTION public.lock_notification_company(p_company_id UUID) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    PERFORM 1 FROM public.company WHERE id = p_company_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown company' USING ERRCODE = 'P0002';
    END IF;
END $$;
ALTER FUNCTION public.lock_notification_company(UUID) OWNER TO procureflow_migrator;
REVOKE ALL ON FUNCTION public.lock_notification_company(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.lock_notification_company(UUID) TO procureflow_worker;
