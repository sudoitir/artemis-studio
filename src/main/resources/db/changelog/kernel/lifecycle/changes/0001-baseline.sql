--liquibase formatted sql

-- The kernel/lifecycle module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-lifecycle-0001-baseline splitStatements:true
CREATE TABLE lifecycle_purge (
    last_run_at timestamp with time zone NOT NULL,
    last_purged bigint NOT NULL,
    store_id text NOT NULL,
    last_error text
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE storage_sample (
    sampled_at timestamp with time zone NOT NULL,
    bytes bigint NOT NULL,
    schema_name text NOT NULL,
    table_name text NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000', autovacuum_analyze_scale_factor='0.02');

ALTER TABLE ONLY lifecycle_purge
    ADD CONSTRAINT pk_lifecycle_purge PRIMARY KEY (store_id);

ALTER TABLE ONLY storage_sample
    ADD CONSTRAINT pk_storage_sample PRIMARY KEY (schema_name, table_name, sampled_at);
--rollback DROP TABLE IF EXISTS storage_sample CASCADE;
--rollback DROP TABLE IF EXISTS lifecycle_purge CASCADE;
