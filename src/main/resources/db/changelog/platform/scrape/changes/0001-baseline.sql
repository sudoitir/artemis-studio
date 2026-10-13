--liquibase formatted sql

-- The platform/scrape module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:platform-scrape-0001-baseline splitStatements:true
CREATE TABLE metric_sample (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid
)
PARTITION BY RANGE (ts);

CREATE TABLE metric_sample_default (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE queue_snapshot (
    ts timestamp with time zone DEFAULT now() NOT NULL,
    message_count bigint DEFAULT 0 NOT NULL,
    consumer_count bigint DEFAULT 0 NOT NULL,
    delivering_count bigint DEFAULT 0 NOT NULL,
    scheduled_count bigint DEFAULT 0 NOT NULL,
    messages_added bigint DEFAULT 0 NOT NULL,
    messages_acked bigint DEFAULT 0 NOT NULL,
    messages_expired bigint DEFAULT 0 NOT NULL,
    address text NOT NULL,
    queue_name text NOT NULL,
    routing_type text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_queue_snapshot_routing CHECK ((routing_type = ANY (ARRAY['ANYCAST'::text, 'MULTICAST'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY metric_sample ATTACH PARTITION metric_sample_default DEFAULT;

ALTER TABLE ONLY metric_sample
    ADD CONSTRAINT pk_metric_sample PRIMARY KEY (cluster_id, subject_type, subject_name, metric, ts);

ALTER TABLE ONLY metric_sample_default
    ADD CONSTRAINT metric_sample_default_pkey PRIMARY KEY (cluster_id, subject_type, subject_name, metric, ts);

ALTER TABLE ONLY queue_snapshot
    ADD CONSTRAINT pk_queue_snapshot PRIMARY KEY (node_id, queue_name);

CREATE INDEX ix_metric_sample_ts_brin ON ONLY metric_sample USING brin (ts) WITH (pages_per_range='32');

CREATE INDEX ix_queue_snapshot_address ON queue_snapshot USING btree (cluster_id, address);

CREATE INDEX ix_queue_snapshot_cluster ON queue_snapshot USING btree (cluster_id);

CREATE INDEX metric_sample_default_ts_idx ON metric_sample_default USING brin (ts) WITH (pages_per_range='32');

ALTER INDEX pk_metric_sample ATTACH PARTITION metric_sample_default_pkey;

ALTER INDEX ix_metric_sample_ts_brin ATTACH PARTITION metric_sample_default_ts_idx;

ALTER TABLE ONLY queue_snapshot
    ADD CONSTRAINT fk_queue_snapshot_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY queue_snapshot
    ADD CONSTRAINT fk_queue_snapshot_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS queue_snapshot CASCADE;
--rollback DROP TABLE IF EXISTS metric_sample CASCADE;
