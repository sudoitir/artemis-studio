--liquibase formatted sql

-- Table sizes sampled by the storage-sample job (ADR-0134), for growth on the storage health
-- page. Kept bounded by the storage-samples store. Never edit this file once released.

--changeset artemis-studio:kernel-lifecycle-0002-storage-sample
CREATE TABLE storage_sample (
    sampled_at timestamp with time zone NOT NULL,
    bytes bigint NOT NULL,
    schema_name text NOT NULL,
    table_name text NOT NULL
);

CREATE INDEX ix_storage_sample_table ON storage_sample USING btree (schema_name, table_name, sampled_at);
