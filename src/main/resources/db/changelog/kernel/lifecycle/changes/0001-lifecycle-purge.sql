--liquibase formatted sql

-- The last purge of each store (ADR-0132), shown on the Data page. Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:kernel-lifecycle-0001-lifecycle-purge
CREATE TABLE lifecycle_purge (
    last_run_at timestamp with time zone NOT NULL,
    last_purged bigint NOT NULL,
    store_id text NOT NULL,
    last_error text,
    CONSTRAINT pk_lifecycle_purge PRIMARY KEY (store_id)
);
