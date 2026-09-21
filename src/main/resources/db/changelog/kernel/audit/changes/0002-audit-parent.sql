--liquibase formatted sql

-- An audit event can belong to a parent event (ADR-0093): each queue a bulk run acts on
-- names the run's event. Nullable, and no foreign key: audit outlives what it names, and a
-- parent is committed before any of its children. Appended, so it sits after the booleans;
-- rewriting the whole trail to reorder one nullable column is not worth it.
-- Never edit this file once released.

--changeset artemis-studio:kernel-audit-0002-audit-parent splitStatements:true
ALTER TABLE audit_event ADD COLUMN parent_id bigint;

CREATE INDEX ix_audit_event_parent ON audit_event USING btree (parent_id) WHERE (parent_id IS NOT NULL);
--rollback DROP INDEX IF EXISTS ix_audit_event_parent;
--rollback ALTER TABLE audit_event DROP COLUMN IF EXISTS parent_id;
