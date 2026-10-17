CREATE TABLE app_identity (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_identity_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_identity_values CHECK (length(btrim(issuer)) > 0 AND length(btrim(subject)) > 0)
);

CREATE TABLE department (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_department_company_id UNIQUE (company_id, id),
    CONSTRAINT uq_department_company_name UNIQUE (company_id, name)
);

CREATE TABLE membership (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    identity_id UUID NOT NULL REFERENCES app_identity(id),
    department_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_membership_company_id UNIQUE (company_id, id),
    CONSTRAINT uq_membership_audit_actor UNIQUE (company_id, id, identity_id),
    CONSTRAINT uq_membership_company_identity UNIQUE (company_id, identity_id),
    CONSTRAINT fk_membership_department FOREIGN KEY (company_id, department_id) REFERENCES department(company_id, id)
);
CREATE UNIQUE INDEX uq_membership_active_identity ON membership(identity_id) WHERE active;

CREATE TABLE membership_role (
    company_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER', 'COMPANY_ADMIN')),
    PRIMARY KEY (company_id, membership_id, role),
    FOREIGN KEY (company_id, membership_id) REFERENCES membership(company_id, id)
);

CREATE TABLE manager_department (
    company_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    department_id UUID NOT NULL,
    PRIMARY KEY (company_id, membership_id, department_id),
    FOREIGN KEY (company_id, membership_id) REFERENCES membership(company_id, id),
    FOREIGN KEY (company_id, department_id) REFERENCES department(company_id, id)
);

CREATE TABLE monthly_budget (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    department_id UUID NOT NULL,
    budget_month DATE NOT NULL CHECK (extract(day FROM budget_month) = 1),
    limit_amount NUMERIC(14, 2) NOT NULL,
    used_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_budget_department FOREIGN KEY (company_id, department_id) REFERENCES department(company_id, id),
    CONSTRAINT uq_budget_period UNIQUE (company_id, department_id, budget_month),
    CONSTRAINT ck_budget_amounts CHECK (0 <= used_amount AND used_amount <= limit_amount)
);

CREATE TABLE audit_entry (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    actor_identity_id UUID NOT NULL REFERENCES app_identity(id),
    actor_membership_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    FOREIGN KEY (company_id, actor_membership_id, actor_identity_id) REFERENCES membership(company_id, id, identity_id)
);
CREATE INDEX ix_audit_company_time ON audit_entry(company_id, occurred_at);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_entry FROM procureflow_app;
REVOKE ALL ON flyway_schema_history FROM procureflow_app;
