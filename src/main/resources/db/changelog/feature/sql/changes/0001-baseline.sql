--liquibase formatted sql

-- The feature/sql module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-sql-0001-baseline splitStatements:true
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE TABLE message_capture_node (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    captured_from timestamp with time zone,
    dropped_estimate bigint DEFAULT 0 NOT NULL,
    held_bytes bigint DEFAULT 0 NOT NULL,
    capture_state text DEFAULT 'PENDING'::text NOT NULL,
    capture_detail text,
    subscription_id uuid NOT NULL,
    node_id uuid NOT NULL,
    CONSTRAINT ck_message_capture_node_state CHECK ((capture_state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'DEGRADED'::text, 'FAILED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE message_index (
    observed_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    message_id bigint NOT NULL,
    timestamp_ms bigint DEFAULT 0 NOT NULL,
    expiration_ms bigint DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    priority integer DEFAULT 4 NOT NULL,
    message_type integer DEFAULT 0 NOT NULL,
    queue_name text NOT NULL,
    address text NOT NULL,
    node_name text NOT NULL,
    correlation_id text,
    group_id text,
    user_id text,
    reply_to text,
    jms_type text,
    body text,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    source_message_id bigint,
    origin text DEFAULT 'SAMPLED'::text NOT NULL,
    orig_address text,
    body_truncated boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_message_index_origin CHECK ((origin = ANY (ARRAY['SAMPLED'::text, 'CAPTURED'::text])))
)
PARTITION BY RANGE (observed_at);

CREATE TABLE message_index_default (
    observed_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    message_id bigint NOT NULL,
    timestamp_ms bigint DEFAULT 0 NOT NULL,
    expiration_ms bigint DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    priority integer DEFAULT 4 NOT NULL,
    message_type integer DEFAULT 0 NOT NULL,
    queue_name text NOT NULL,
    address text NOT NULL,
    node_name text NOT NULL,
    correlation_id text,
    group_id text,
    user_id text,
    reply_to text,
    jms_type text,
    body text,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    source_message_id bigint,
    origin text DEFAULT 'SAMPLED'::text NOT NULL,
    orig_address text,
    body_truncated boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_message_index_origin CHECK ((origin = ANY (ARRAY['SAMPLED'::text, 'CAPTURED'::text])))
)
WITH (fillfactor='90', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.02');

CREATE TABLE message_index_subscription (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    capture_from timestamp with time zone DEFAULT now() NOT NULL,
    interval_ms bigint DEFAULT 5000 NOT NULL,
    retention_days integer DEFAULT 7 NOT NULL,
    queue_pattern text NOT NULL,
    created_by text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    ring_size bigint DEFAULT 10000 NOT NULL,
    max_bytes bigint DEFAULT '5368709120'::bigint NOT NULL,
    max_rate integer DEFAULT 500 NOT NULL,
    body_cap_bytes integer DEFAULT 262144 NOT NULL,
    mode text DEFAULT 'SAMPLE'::text NOT NULL,
    filter_string text,
    CONSTRAINT ck_message_index_subscription_body_cap CHECK (((body_cap_bytes >= 1024) AND (body_cap_bytes <= 16777216))),
    CONSTRAINT ck_message_index_subscription_interval CHECK ((interval_ms >= 1000)),
    CONSTRAINT ck_message_index_subscription_max_bytes CHECK ((max_bytes > 0)),
    CONSTRAINT ck_message_index_subscription_mode CHECK ((mode = ANY (ARRAY['SAMPLE'::text, 'CAPTURE'::text]))),
    CONSTRAINT ck_message_index_subscription_rate CHECK (((max_rate >= 1) AND (max_rate <= 1000000))),
    CONSTRAINT ck_message_index_subscription_retention CHECK (((retention_days >= 1) AND (retention_days <= 90))),
    CONSTRAINT ck_message_index_subscription_ring CHECK (((ring_size >= 100) AND (ring_size <= 10000000)))
);

ALTER TABLE ONLY message_index ATTACH PARTITION message_index_default DEFAULT;

ALTER TABLE ONLY message_index
    ADD CONSTRAINT pk_message_index PRIMARY KEY (node_id, queue_name, message_id, observed_at);

ALTER TABLE ONLY message_index_default
    ADD CONSTRAINT message_index_default_pkey PRIMARY KEY (node_id, queue_name, message_id, observed_at);

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT pk_message_capture_node PRIMARY KEY (subscription_id, node_id);

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT pk_message_index_subscription PRIMARY KEY (id);

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT uq_message_index_subscription UNIQUE (cluster_id, queue_pattern);

CREATE INDEX ix_message_index_body ON ONLY message_index USING gin (body gin_trgm_ops);

CREATE INDEX ix_message_index_body_fts ON ONLY message_index USING gin (to_tsvector('simple'::regconfig, body)) WHERE ((body IS NOT NULL) AND (message_type <> 4));

CREATE INDEX ix_message_index_lookup ON ONLY message_index USING btree (cluster_id, queue_name, observed_at DESC);

CREATE INDEX ix_message_index_props ON ONLY message_index USING gin (props jsonb_path_ops);

CREATE INDEX message_index_default_body_idx ON message_index_default USING gin (body gin_trgm_ops);

CREATE INDEX message_index_default_cluster_id_queue_name_observed_at_idx ON message_index_default USING btree (cluster_id, queue_name, observed_at DESC);

CREATE INDEX message_index_default_props_idx ON message_index_default USING gin (props jsonb_path_ops);

CREATE INDEX message_index_default_to_tsvector_idx ON message_index_default USING gin (to_tsvector('simple'::regconfig, body)) WHERE ((body IS NOT NULL) AND (message_type <> 4));

ALTER INDEX ix_message_index_body ATTACH PARTITION message_index_default_body_idx;

ALTER INDEX ix_message_index_lookup ATTACH PARTITION message_index_default_cluster_id_queue_name_observed_at_idx;

ALTER INDEX pk_message_index ATTACH PARTITION message_index_default_pkey;

ALTER INDEX ix_message_index_props ATTACH PARTITION message_index_default_props_idx;

ALTER INDEX ix_message_index_body_fts ATTACH PARTITION message_index_default_to_tsvector_idx;

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT fk_message_capture_node_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT fk_message_capture_node_subscription FOREIGN KEY (subscription_id) REFERENCES message_index_subscription(id) ON DELETE CASCADE;

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT fk_message_index_subscription_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS message_capture_node CASCADE;
--rollback DROP TABLE IF EXISTS message_index CASCADE;
--rollback DROP TABLE IF EXISTS message_index_subscription CASCADE;
--rollback DROP EXTENSION IF EXISTS pg_trgm;
