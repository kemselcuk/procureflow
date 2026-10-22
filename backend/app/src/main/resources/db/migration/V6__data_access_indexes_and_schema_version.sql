-- P8.2 additive data-access indexes. On a deterministic populated fixture (50,000
-- notification_delivery rows and 50,000 audit_entry rows across two companies), EXPLAIN
-- (ANALYZE, BUFFERS, FORMAT JSON) showed that these candidates lower cost and shared buffers for
-- the company/outcome count and remove the Incremental Sort from the company audit page. Full
-- evidence is recorded in the P8.2 plan logs; this is a local fixture measurement, not a
-- universal benchmark or SLA.
-- This is an index-only migration: no table data is rewritten. Downgrade disposition is dropping
-- the two indexes below; no data migration is required.
CREATE INDEX ix_notification_delivery_company_outcome ON notification_delivery(company_id, outcome);
CREATE INDEX ix_audit_company_time_id ON audit_entry(company_id, occurred_at DESC, id DESC);

-- The runtime application verifies the latest Flyway version at startup with its own credential.
-- V2 revoked all privileges on flyway_schema_history; restore read-only access only.
GRANT SELECT ON flyway_schema_history TO procureflow_app;
