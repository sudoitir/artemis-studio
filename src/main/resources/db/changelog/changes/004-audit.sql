--liquibase formatted sql

-- Audit: one row per management action, written in the same transaction as the
-- command (before the broker call), then updated with the outcome. Append-only
-- and insert-heavy, so autovacuum is tuned for inserts and fillfactor stays 100
-- (no in-place updates worth leaving space for once outcome is set).

--changeset artemis-studio:004-audit-event
CREATE TABLE audit_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    affected_count  BIGINT,
    action          TEXT NOT NULL,
    target_type     TEXT,                  -- QUEUE | ADDRESS | MESSAGE | CLUSTER | USER ...
    target_name     TEXT,
    username        TEXT,                  -- denormalised: survives user deletion
    outcome         TEXT NOT NULL DEFAULT 'PENDING',
    error           TEXT,
    request_id      TEXT,
    params          JSONB,
    source_ip       INET,
    user_id         UUID,
    cluster_id      UUID,
    node_id         UUID,
    dry_run         BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_event_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_event_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE SET NULL,
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('PENDING', 'SUCCESS', 'FAILURE'))
);

CREATE INDEX ix_audit_event_ts       ON audit_event (ts DESC);
CREATE INDEX ix_audit_event_cluster  ON audit_event (cluster_id, ts DESC);
CREATE INDEX ix_audit_event_user     ON audit_event (user_id, ts DESC);
--rollback DROP TABLE audit_event;

--changeset artemis-studio:004-audit-event-autovacuum
--comment: insert-heavy table; vacuum on insert volume, not dead-tuple ratio.
ALTER TABLE audit_event SET (
    fillfactor = 100,
    autovacuum_vacuum_insert_scale_factor = 0.0,
    autovacuum_vacuum_insert_threshold = 5000,
    autovacuum_analyze_scale_factor = 0.02
);
--rollback ALTER TABLE audit_event RESET (fillfactor, autovacuum_vacuum_insert_scale_factor, autovacuum_vacuum_insert_threshold, autovacuum_analyze_scale_factor);
