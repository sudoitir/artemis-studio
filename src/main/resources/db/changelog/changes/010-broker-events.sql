--liquibase formatted sql

-- Raw activemq.notifications history: one row per notification per node
-- (ADR-0026, ADR-0028). Distinct from rr_event (007), which is flow-scoped and
-- correlated (Phase 5). Append-only, high churn, reaped on a retention window.
-- seq is the PK on purpose: this is a log, and a monotonic bigint doubles as the
-- SSE Last-Event-ID cursor (slice 3).

--changeset artemis-studio:010-broker-event
CREATE TABLE broker_event (
    occurred_at     TIMESTAMPTZ NOT NULL,             -- _AMQ_NotifTimestamp
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    seq             BIGINT GENERATED ALWAYS AS IDENTITY,
    type            TEXT NOT NULL,
    address         TEXT,
    routing_name    TEXT,
    consumer_name   TEXT,
    session_name    TEXT,
    connection_name TEXT,
    remote_address  TEXT,
    username        TEXT,
    props           JSONB,                            -- every _AMQ_* verbatim
    cluster_id      UUID NOT NULL,
    node_id         UUID,
    CONSTRAINT pk_broker_event PRIMARY KEY (seq),
    CONSTRAINT fk_broker_event_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT fk_broker_event_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE SET NULL
);

CREATE INDEX ix_broker_event_cluster_seq  ON broker_event (cluster_id, seq DESC);
CREATE INDEX ix_broker_event_cluster_time ON broker_event (cluster_id, occurred_at DESC);
CREATE INDEX ix_broker_event_type         ON broker_event (cluster_id, type, occurred_at DESC);
--rollback DROP TABLE broker_event;

--changeset artemis-studio:010-broker-event-autovacuum
--comment: append-only, high churn, reaped on a retention window; tune for insert
--         volume like rr_event, no fillfactor headroom needed.
ALTER TABLE broker_event SET (
    fillfactor = 100,
    autovacuum_vacuum_insert_scale_factor = 0.0,
    autovacuum_vacuum_insert_threshold = 5000
);
--rollback ALTER TABLE broker_event RESET (fillfactor, autovacuum_vacuum_insert_scale_factor, autovacuum_vacuum_insert_threshold);
