--liquibase formatted sql

-- A request refused for lack of a permission is an audit event too, with the outcome REFUSED
-- (audit-log spec). Its affected_count is how many identical refusals the event stands for.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-audit-0003-refused-outcome splitStatements:true
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_outcome;

ALTER TABLE audit_event
    ADD CONSTRAINT ck_audit_event_outcome CHECK (outcome = ANY (ARRAY['PENDING'::text, 'SUCCESS'::text, 'FAILURE'::text, 'REFUSED'::text]));
--rollback ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_outcome;
--rollback ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_outcome CHECK (outcome = ANY (ARRAY['PENDING'::text, 'SUCCESS'::text, 'FAILURE'::text]));
