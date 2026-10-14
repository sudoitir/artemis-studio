--liquibase formatted sql

-- The kernel/core module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-core-0001-baseline splitStatements:true
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- One row per running heavy request, so a user's limit holds across replicas. A request that is gone
-- (a crashed replica) lapses at expires_at and its slot is taken over by the next claim.
CREATE UNLOGGED TABLE heavy_permit (
    expires_at timestamp with time zone NOT NULL,
    slot integer NOT NULL,
    owner text NOT NULL,
    endpoint text NOT NULL,
    replica_id uuid NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY heavy_permit
    ADD CONSTRAINT pk_heavy_permit PRIMARY KEY (owner, endpoint, slot);

--rollback DROP TABLE IF EXISTS heavy_permit CASCADE;
--rollback DROP EXTENSION IF EXISTS pg_trgm;
