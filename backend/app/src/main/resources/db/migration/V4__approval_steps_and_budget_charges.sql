ALTER TABLE purchase_request ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE purchase_request ADD COLUMN budget_month DATE GENERATED ALWAYS AS
    (date_trunc('month', approved_at AT TIME ZONE 'UTC')::date) STORED;
ALTER TABLE purchase_request ADD CONSTRAINT ck_request_final_time CHECK
    ((status = 'APPROVED') = (approved_at IS NOT NULL)
    AND (approved_at IS NULL OR (approved_at >= submitted_at AND updated_at = approved_at)));
ALTER TABLE monthly_budget ADD CONSTRAINT uq_budget_charge_scope UNIQUE (company_id, id, department_id, budget_month);

CREATE TABLE approval_step (
    company_id UUID NOT NULL,
    request_id UUID NOT NULL,
    step VARCHAR(16) NOT NULL CHECK (step IN ('DEPARTMENT', 'FINANCE')),
    request_version BIGINT NOT NULL CHECK (request_version > 0),
    actor_identity_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id UUID NOT NULL,
    PRIMARY KEY (company_id, request_id, step),
    UNIQUE (company_id, request_id, actor_identity_id),
    UNIQUE (company_id, request_id, request_version),
    FOREIGN KEY (company_id, request_id) REFERENCES purchase_request(company_id, id),
    FOREIGN KEY (company_id, actor_membership_id, actor_identity_id) REFERENCES membership(company_id, id, identity_id),
    FOREIGN KEY (company_id, request_id, request_version) REFERENCES request_history(company_id, request_id, request_version)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE budget_charge (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    request_id UUID NOT NULL,
    department_id UUID NOT NULL,
    budget_id UUID NOT NULL,
    budget_month DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    approval_version BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id UUID NOT NULL,
    UNIQUE (company_id, request_id),
    FOREIGN KEY (company_id, request_id) REFERENCES purchase_request(company_id, id),
    FOREIGN KEY (company_id, budget_id, department_id, budget_month) REFERENCES monthly_budget(company_id, id, department_id, budget_month),
    FOREIGN KEY (company_id, request_id, approval_version) REFERENCES approval_step(company_id, request_id, request_version)
        DEFERRABLE INITIALLY DEFERRED
);
REVOKE UPDATE, DELETE, TRUNCATE ON approval_step, budget_charge FROM procureflow_app;

CREATE FUNCTION guard_approval_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM purchase_request r WHERE r.company_id = NEW.company_id AND r.id = NEW.request_id
        AND r.version = NEW.request_version AND r.correlation_id = NEW.correlation_id AND r.updated_at = NEW.occurred_at
        AND r.requester_identity_id <> NEW.actor_identity_id AND (
            (NEW.step = 'DEPARTMENT' AND r.department_approver_identity_id = NEW.actor_identity_id
                AND r.department_approver_membership_id = NEW.actor_membership_id
                AND ((r.status = 'AWAITING_FINANCE_APPROVAL' AND r.amount > 1000)
                    OR (r.status = 'APPROVED' AND r.amount <= 1000))) OR
            (NEW.step = 'FINANCE' AND r.status = 'APPROVED' AND r.amount > 1000
                AND r.department_approver_identity_id <> NEW.actor_identity_id))) THEN
        RAISE EXCEPTION 'Approval step does not match request/actor/version/time' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_approval_step BEFORE INSERT ON approval_step FOR EACH ROW EXECUTE FUNCTION guard_approval_step();

-- Validate the complete transaction, not the temporary ordering of its inserts.
CREATE FUNCTION require_request_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r purchase_request%ROWTYPE;
    request_key UUID;
    department_steps INTEGER;
    finance_steps INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'purchase_request' THEN request_key := NEW.id; ELSE request_key := NEW.request_id; END IF;
    SELECT * INTO STRICT r FROM purchase_request WHERE company_id = NEW.company_id AND id = request_key;
    SELECT count(*) FILTER (WHERE step = 'DEPARTMENT'), count(*) FILTER (WHERE step = 'FINANCE')
        INTO department_steps, finance_steps FROM approval_step WHERE company_id = r.company_id AND request_id = r.id;
    IF department_steps <> (CASE WHEN r.department_approver_identity_id IS NULL THEN 0 ELSE 1 END)
        OR finance_steps <> (CASE WHEN r.status = 'APPROVED' AND r.amount > 1000 THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION 'Approval steps do not match request route' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM approval_step s LEFT JOIN request_history h ON h.company_id = s.company_id
        AND h.request_id = s.request_id AND h.request_version = s.request_version
        WHERE s.company_id = r.company_id AND s.request_id = r.id AND
        (h.id IS NULL OR h.actor_identity_id <> s.actor_identity_id OR h.actor_membership_id <> s.actor_membership_id
        OR h.correlation_id <> s.correlation_id OR h.occurred_at <> s.occurred_at
        OR h.action <> CASE WHEN s.step = 'DEPARTMENT' AND r.amount > 1000 THEN 'request.department_approved' ELSE 'request.approved' END
        OR (s.step = 'DEPARTMENT' AND (s.actor_identity_id, s.actor_membership_id)
            IS DISTINCT FROM (r.department_approver_identity_id, r.department_approver_membership_id)))) THEN
        RAISE EXCEPTION 'Approval/history actor or correlation mismatch' USING ERRCODE = '23514';
    END IF;
    IF r.status = 'APPROVED' THEN
        IF NOT EXISTS (SELECT 1 FROM budget_charge c WHERE c.company_id = r.company_id AND c.request_id = r.id
            AND c.department_id = r.department_id AND c.amount = r.amount AND c.budget_month = r.budget_month
            AND c.occurred_at = r.approved_at AND c.correlation_id = r.correlation_id AND c.approval_version = r.version) THEN
            RAISE EXCEPTION 'Approved request lacks its exact final charge' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM budget_charge c WHERE c.company_id = r.company_id AND c.request_id = r.id) THEN
        RAISE EXCEPTION 'Non-final request cannot be charged' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER require_request_settlement AFTER INSERT OR UPDATE ON purchase_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_request_settlement();
CREATE CONSTRAINT TRIGGER require_request_settlement AFTER INSERT ON approval_step
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_request_settlement();
CREATE CONSTRAINT TRIGGER require_request_settlement AFTER INSERT ON budget_charge
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_request_settlement();
