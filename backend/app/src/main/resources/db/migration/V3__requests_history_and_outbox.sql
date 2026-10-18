ALTER TABLE audit_entry ADD COLUMN correlation_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE TABLE purchase_request (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    requester_identity_id UUID NOT NULL,
    requester_membership_id UUID NOT NULL,
    department_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    justification VARCHAR(2000) NOT NULL CHECK (length(btrim(justification)) > 0),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'AWAITING_DEPARTMENT_APPROVAL',
        'AWAITING_FINANCE_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    department_approver_identity_id UUID,
    department_approver_membership_id UUID,
    correlation_id UUID NOT NULL,
    UNIQUE (company_id, id),
    FOREIGN KEY (company_id, requester_membership_id, requester_identity_id) REFERENCES membership(company_id, id, identity_id),
    FOREIGN KEY (company_id, department_id) REFERENCES department(company_id, id),
    FOREIGN KEY (company_id, department_approver_membership_id, department_approver_identity_id)
        REFERENCES membership(company_id, id, identity_id),
    CHECK ((department_approver_identity_id IS NULL) = (department_approver_membership_id IS NULL)),
    CHECK (department_approver_identity_id IS NULL OR department_approver_identity_id <> requester_identity_id),
    CHECK ((status = 'DRAFT') = (submitted_at IS NULL)),
    CHECK (status NOT IN ('AWAITING_FINANCE_APPROVAL', 'APPROVED') OR department_approver_identity_id IS NOT NULL),
    CHECK (status NOT IN ('DRAFT', 'AWAITING_DEPARTMENT_APPROVAL') OR department_approver_identity_id IS NULL),
    CHECK (status <> 'AWAITING_FINANCE_APPROVAL' OR amount > 1000.00),
    CHECK (updated_at >= created_at AND (submitted_at IS NULL OR submitted_at >= created_at))
);
CREATE INDEX ix_request_owner_page ON purchase_request(company_id, requester_identity_id, created_at DESC, id DESC);
CREATE INDEX ix_request_department_queue ON purchase_request(company_id, department_id, status, created_at DESC, id DESC);
CREATE INDEX ix_request_finance_queue ON purchase_request(company_id, status, created_at DESC, id DESC);

CREATE FUNCTION guard_request_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.company_id, NEW.requester_identity_id, NEW.requester_membership_id, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.company_id, OLD.requester_identity_id, OLD.requester_membership_id, OLD.created_at)
        OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Immutable request owner or invalid version' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.title, NEW.justification, NEW.amount, NEW.department_id, NEW.submitted_at)
        IS DISTINCT FROM (OLD.title, OLD.justification, OLD.amount, OLD.department_id, OLD.submitted_at) THEN
        RAISE EXCEPTION 'Submitted request content is immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'AWAITING_DEPARTMENT_APPROVAL'))
        OR (OLD.status = 'AWAITING_DEPARTMENT_APPROVAL' AND NEW.status IN ('AWAITING_FINANCE_APPROVAL', 'REJECTED', 'CANCELLED'))
        OR (OLD.status = 'AWAITING_DEPARTMENT_APPROVAL' AND NEW.status = 'APPROVED' AND OLD.amount <= 1000.00)
        OR (OLD.status = 'AWAITING_FINANCE_APPROVAL' AND NEW.status IN ('APPROVED', 'REJECTED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'Invalid request transition' USING ERRCODE = '23514';
    END IF;
    IF (NEW.department_approver_identity_id, NEW.department_approver_membership_id)
        IS DISTINCT FROM (OLD.department_approver_identity_id, OLD.department_approver_membership_id)
        AND NOT (OLD.status = 'AWAITING_DEPARTMENT_APPROVAL' AND NEW.status IN ('AWAITING_FINANCE_APPROVAL', 'APPROVED')) THEN
        RAISE EXCEPTION 'Prior approver cannot be rewritten' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_request_update BEFORE UPDATE ON purchase_request FOR EACH ROW EXECUTE FUNCTION guard_request_update();

CREATE TABLE request_history (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_version BIGINT NOT NULL CHECK (request_version >= 0),
    action VARCHAR(40) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_identity_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    reason VARCHAR(1000),
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (company_id, request_id, request_version),
    FOREIGN KEY (company_id, request_id) REFERENCES purchase_request(company_id, id),
    FOREIGN KEY (company_id, actor_membership_id, actor_identity_id) REFERENCES membership(company_id, id, identity_id),
    CHECK (action <> 'request.rejected' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

CREATE FUNCTION guard_request_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM purchase_request r WHERE r.company_id = NEW.company_id AND r.id = NEW.request_id
        AND r.version = NEW.request_version AND r.status = NEW.to_status AND r.correlation_id = NEW.correlation_id) THEN
        RAISE EXCEPTION 'History does not match the request version/state/correlation' USING ERRCODE = '23514';
    END IF;
    IF NEW.request_version = 0 THEN
        IF NEW.action <> 'request.created' OR NEW.from_status IS NOT NULL OR NEW.to_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Invalid initial request history' USING ERRCODE = '23514';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM request_history h WHERE h.company_id = NEW.company_id AND h.request_id = NEW.request_id
        AND h.request_version = NEW.request_version - 1 AND h.to_status = NEW.from_status) THEN
        RAISE EXCEPTION 'History is not a continuous version chain' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_request_history BEFORE INSERT ON request_history FOR EACH ROW EXECUTE FUNCTION guard_request_history();

CREATE TABLE api_command (
    company_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('CREATE', 'SUBMIT')),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    response_status INTEGER CHECK (response_status IN (200, 201)),
    response_json JSONB CHECK (jsonb_typeof(response_json) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (company_id, actor_identity_id, idempotency_key),
    FOREIGN KEY (company_id, actor_identity_id) REFERENCES membership(company_id, identity_id),
    CHECK ((response_json IS NULL) = (response_status IS NULL))
);
-- Claims exist only within the command transaction; an unfinished claim must never become visible.
CREATE FUNCTION require_command_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM api_command WHERE company_id = NEW.company_id AND actor_identity_id = NEW.actor_identity_id
        AND idempotency_key = NEW.idempotency_key AND response_json IS NULL) THEN
        RAISE EXCEPTION 'Command result is incomplete' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER require_command_result AFTER INSERT OR UPDATE ON api_command
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_command_result();
REVOKE UPDATE, DELETE, TRUNCATE ON api_command FROM procureflow_app;
GRANT UPDATE(response_status, response_json) ON api_command TO procureflow_app;

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    request_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN ('request.submitted', 'request.department_approved',
        'request.approved', 'request.rejected', 'request.cancelled')),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    envelope JSONB NOT NULL,
    UNIQUE (company_id, id),
    FOREIGN KEY (company_id, request_id) REFERENCES purchase_request(company_id, id),
    CHECK ((envelope->>'eventId' = id::text AND envelope->>'companyId' = company_id::text
        AND envelope->>'requestId' = request_id::text AND envelope->>'type' = event_type
        AND envelope->>'correlationId' = correlation_id::text AND envelope->>'schemaVersion' = '1') IS TRUE)
);
CREATE INDEX ix_outbox_event_time ON outbox_event(occurred_at, id);

CREATE TABLE outbox_recipient (
    company_id UUID NOT NULL,
    event_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    in_app BOOLEAN NOT NULL DEFAULT true CHECK (in_app),
    email BOOLEAN NOT NULL DEFAULT true CHECK (email),
    PRIMARY KEY (company_id, event_id, identity_id),
    FOREIGN KEY (company_id, event_id) REFERENCES outbox_event(company_id, id),
    FOREIGN KEY (company_id, membership_id, identity_id) REFERENCES membership(company_id, id, identity_id)
);
REVOKE UPDATE, DELETE, TRUNCATE ON request_history, outbox_event, outbox_recipient FROM procureflow_app;
