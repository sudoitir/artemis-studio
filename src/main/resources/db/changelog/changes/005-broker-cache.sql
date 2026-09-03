--liquibase formatted sql

-- Cached broker-derived state. Disposable: rebuilt from the brokers on every
-- scrape. NOT the source of truth. Two access patterns, two tables:
--   queue_snapshot  latest state per queue, constant upserts  -> fillfactor + HOT
--   metric_sample   append-only timeseries, range-partitioned -> insert-tuned

--changeset artemis-studio:005-cache-queue-snapshot
CREATE TABLE queue_snapshot (
    ts               TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_count    BIGINT NOT NULL DEFAULT 0,
    consumer_count   BIGINT NOT NULL DEFAULT 0,
    delivering_count BIGINT NOT NULL DEFAULT 0,
    scheduled_count  BIGINT NOT NULL DEFAULT 0,
    messages_added   BIGINT NOT NULL DEFAULT 0,
    messages_acked   BIGINT NOT NULL DEFAULT 0,
    messages_expired BIGINT NOT NULL DEFAULT 0,
    address          TEXT NOT NULL,
    queue_name       TEXT NOT NULL,
    routing_type     TEXT NOT NULL,
    cluster_id       UUID NOT NULL,
    node_id          UUID NOT NULL,
    durable          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_queue_snapshot PRIMARY KEY (node_id, queue_name),
    CONSTRAINT fk_queue_snapshot_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT fk_queue_snapshot_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE CASCADE,
    CONSTRAINT ck_queue_snapshot_routing CHECK (routing_type IN ('ANYCAST', 'MULTICAST'))
);

CREATE INDEX ix_queue_snapshot_cluster ON queue_snapshot (cluster_id);
CREATE INDEX ix_queue_snapshot_address ON queue_snapshot (cluster_id, address);
--rollback DROP TABLE queue_snapshot;

--changeset artemis-studio:005-cache-queue-snapshot-autovacuum
--comment: every scrape rewrites every row; leave page space for HOT updates and
--         vacuum aggressively to keep the visibility map warm.
ALTER TABLE queue_snapshot SET (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_vacuum_cost_delay = 2
);
--rollback ALTER TABLE queue_snapshot RESET (fillfactor, autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor, autovacuum_vacuum_cost_delay);

--changeset artemis-studio:005-cache-metric-sample
--comment: range-partitioned by ts. A default partition catches rows until the
--         Phase 6 retention job creates daily partitions, so inserts never fail.
CREATE TABLE metric_sample (
    ts            TIMESTAMPTZ NOT NULL,
    value         DOUBLE PRECISION NOT NULL,
    subject_type  TEXT NOT NULL,           -- BROKER | ADDRESS | QUEUE
    subject_name  TEXT NOT NULL,
    metric        TEXT NOT NULL,
    cluster_id    UUID NOT NULL,
    node_id       UUID NOT NULL
) PARTITION BY RANGE (ts);

CREATE TABLE metric_sample_default PARTITION OF metric_sample DEFAULT;

CREATE INDEX ix_metric_sample_lookup
    ON metric_sample (cluster_id, subject_type, subject_name, metric, ts);
CREATE INDEX ix_metric_sample_ts_brin
    ON metric_sample USING BRIN (ts) WITH (pages_per_range = 32);
--rollback DROP TABLE metric_sample;

--changeset artemis-studio:005-cache-metric-sample-autovacuum
--comment: append-only; tune for insert volume, no fillfactor headroom needed.
ALTER TABLE metric_sample_default SET (
    fillfactor = 100,
    autovacuum_vacuum_insert_scale_factor = 0.0,
    autovacuum_vacuum_insert_threshold = 10000,
    autovacuum_analyze_scale_factor = 0.05
);
--rollback ALTER TABLE metric_sample_default RESET (fillfactor, autovacuum_vacuum_insert_scale_factor, autovacuum_vacuum_insert_threshold, autovacuum_analyze_scale_factor);
