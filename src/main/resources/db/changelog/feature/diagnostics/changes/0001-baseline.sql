--liquibase formatted sql

-- The feature/diagnostics module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-diagnostics-0001-baseline splitStatements:true
CREATE UNLOGGED TABLE diagnostics_snapshot (
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    owner text NOT NULL,
    sections text NOT NULL,
    id uuid NOT NULL
)
WITH (autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY diagnostics_snapshot
    ADD CONSTRAINT pk_diagnostics_snapshot PRIMARY KEY (id);

CREATE INDEX ix_diagnostics_snapshot_expires_at ON diagnostics_snapshot USING btree (expires_at);
--rollback DROP TABLE IF EXISTS diagnostics_snapshot CASCADE;
