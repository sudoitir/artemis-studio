--liquibase formatted sql

-- The kernel/replica module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-replica-0001-baseline splitStatements:true
CREATE TABLE studio_replica (
    started_at timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL,
    stopped_at timestamp with time zone,
    host text NOT NULL,
    version text NOT NULL,
    state text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    CONSTRAINT ck_studio_replica_state CHECK ((state = ANY (ARRAY['starting'::text, 'ready'::text, 'draining'::text, 'stopped'::text])))
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY studio_replica
    ADD CONSTRAINT pk_studio_replica PRIMARY KEY (id);
--rollback DROP TABLE IF EXISTS studio_replica CASCADE;
