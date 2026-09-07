--liquibase formatted sql

-- The SQL Console's opt-in historical message index (ADR-0059). Unlike
-- queue_snapshot and metric_sample this is NOT rebuildable from the brokers — a
-- consumed message cannot be re-observed — and it holds application payload, so
-- it is off until an operator creates a subscription, short-retention by default,
-- and droppable in one action.
--
-- Column order follows the project convention: 8-byte-aligned types first, then
-- 4-byte, then uuid and boolean last.

--changeset artemis-studio:021-pg-trgm
--comment: trigram index support for `body LIKE '%...%'`, which has no btree answer.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
--rollback DROP EXTENSION IF EXISTS pg_trgm;

--changeset artemis-studio:021-message-index-subscription
CREATE TABLE message_index_subscription (
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    capture_from    TIMESTAMPTZ NOT NULL DEFAULT now(),
    interval_ms     BIGINT NOT NULL DEFAULT 5000,
    retention_days  INTEGER NOT NULL DEFAULT 7,
    queue_pattern   TEXT NOT NULL,
    created_by      TEXT,
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id      UUID NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_message_index_subscription PRIMARY KEY (id),
    CONSTRAINT fk_message_index_subscription_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT uq_message_index_subscription UNIQUE (cluster_id, queue_pattern),
    CONSTRAINT ck_message_index_subscription_retention CHECK (retention_days BETWEEN 1 AND 90),
    CONSTRAINT ck_message_index_subscription_interval CHECK (interval_ms >= 1000)
);
--rollback DROP TABLE message_index_subscription;

--changeset artemis-studio:021-message-index
--comment: range-partitioned on observed_at so retention drops whole partitions
--         rather than deleting rows; a default partition catches everything until
--         MessageIndexPartitionMaintainer has run, so an insert never fails.
CREATE TABLE message_index (
    observed_at    TIMESTAMPTZ NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL,
    message_id     BIGINT NOT NULL,
    timestamp_ms   BIGINT NOT NULL DEFAULT 0,
    expiration_ms  BIGINT NOT NULL DEFAULT 0,
    size_bytes     BIGINT NOT NULL DEFAULT 0,
    priority       INTEGER NOT NULL DEFAULT 4,
    message_type   INTEGER NOT NULL DEFAULT 0,
    queue_name     TEXT NOT NULL,
    address        TEXT NOT NULL,
    node_name      TEXT NOT NULL,
    correlation_id TEXT,
    group_id       TEXT,
    user_id        TEXT,
    reply_to       TEXT,
    jms_type       TEXT,
    body           TEXT,
    props          JSONB NOT NULL DEFAULT '{}'::jsonb,
    cluster_id     UUID NOT NULL,
    node_id        UUID NOT NULL,
    durable        BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_message_index PRIMARY KEY (node_id, queue_name, message_id, observed_at)
) PARTITION BY RANGE (observed_at);

CREATE TABLE message_index_default PARTITION OF message_index DEFAULT;

-- The console's own access path: one cluster, a queue pattern, newest first.
CREATE INDEX ix_message_index_lookup
    ON message_index (cluster_id, queue_name, observed_at DESC);

-- Application properties are queried by name, so containment is the operation
-- that matters; jsonb_path_ops is the smaller, faster index for exactly that.
CREATE INDEX ix_message_index_props
    ON message_index USING GIN (props jsonb_path_ops);

-- `body LIKE '%fragment%'` is the query this feature exists for and the one no
-- btree can serve.
CREATE INDEX ix_message_index_body
    ON message_index USING GIN (body gin_trgm_ops);
--rollback DROP TABLE message_index;

--changeset artemis-studio:021-message-index-autovacuum
--comment: insert-heavy with one update per re-observation (last_seen_at); leave
--         page space for HOT updates and analyze often enough that the planner
--         keeps choosing the GIN indexes. Postgres refuses storage parameters on a
--         partitioned parent, so they go on the partition — the same shape as
--         metric_sample_default in changeset 005, and MessageIndexPartitionMaintainer
--         repeats them on every partition it creates.
ALTER TABLE message_index_default SET (
    fillfactor = 90,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);
--rollback ALTER TABLE message_index_default RESET (fillfactor, autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor);
