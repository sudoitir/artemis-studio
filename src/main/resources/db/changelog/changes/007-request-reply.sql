--liquibase formatted sql

-- Request-reply tracing — the flagship. Which request addresses to trace
-- (rr_expectation), the reconstructed flows (rr_flow), and the raw observed
-- lifecycle events behind each flow (rr_event). Populated from Phase 5,
-- driven by the activemq.notifications stream, not by polling browse.

--changeset artemis-studio:007-rr-expectation
CREATE TABLE rr_expectation (
    deadline_ms      INTEGER,              -- fallback when messages carry no expiry
    sample_per_min   INTEGER NOT NULL DEFAULT 10,
    request_address  TEXT NOT NULL,
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id       UUID NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_rr_expectation PRIMARY KEY (id),
    CONSTRAINT fk_rr_expectation_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT uq_rr_expectation UNIQUE (cluster_id, request_address)
);
--rollback DROP TABLE rr_expectation;

--changeset artemis-studio:007-rr-flow
CREATE TABLE rr_flow (
    requested_at       TIMESTAMPTZ,
    replied_at         TIMESTAMPTZ,
    deadline_at        TIMESTAMPTZ,
    latency_ms         BIGINT,
    request_address    TEXT NOT NULL,
    reply_destination  TEXT,
    reply_kind         TEXT NOT NULL,      -- TEMP_QUEUE | SHARED_QUEUE
    state              TEXT NOT NULL,
    correlation_id     TEXT,
    requester_session  TEXT,
    responder_session  TEXT,
    id                 UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id         UUID NOT NULL,
    CONSTRAINT pk_rr_flow PRIMARY KEY (id),
    CONSTRAINT fk_rr_flow_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT ck_rr_flow_reply_kind CHECK (reply_kind IN ('TEMP_QUEUE', 'SHARED_QUEUE')),
    CONSTRAINT ck_rr_flow_state CHECK (state IN (
        'AWAITING_REPLY', 'COMPLETED', 'TIMED_OUT',
        'ORPHANED', 'RESPONDER_DROPPED', 'ORPHANED_REPLY'))
);

CREATE INDEX ix_rr_flow_cluster_state ON rr_flow (cluster_id, state);
CREATE INDEX ix_rr_flow_address       ON rr_flow (cluster_id, request_address, requested_at DESC);
CREATE INDEX ix_rr_flow_correlation   ON rr_flow (cluster_id, correlation_id);
--rollback DROP TABLE rr_flow;

--changeset artemis-studio:007-rr-flow-autovacuum
--comment: insert on first observation, a few updates as the flow resolves.
ALTER TABLE rr_flow SET (
    fillfactor = 90,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.05
);
--rollback ALTER TABLE rr_flow RESET (fillfactor, autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor);

--changeset artemis-studio:007-rr-event
CREATE TABLE rr_event (
    ts       TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind     TEXT NOT NULL,                -- BINDING_ADDED, CONSUMER_CREATED, REPLY_SEEN ...
    detail   JSONB,
    flow_id  UUID NOT NULL,
    node_id  UUID,
    CONSTRAINT fk_rr_event_flow FOREIGN KEY (flow_id)
        REFERENCES rr_flow (id) ON DELETE CASCADE
);

CREATE INDEX ix_rr_event_flow ON rr_event (flow_id, ts);
--rollback DROP TABLE rr_event;

--changeset artemis-studio:007-rr-event-autovacuum
--comment: append-only child of rr_flow.
ALTER TABLE rr_event SET (
    fillfactor = 100,
    autovacuum_vacuum_insert_scale_factor = 0.0,
    autovacuum_vacuum_insert_threshold = 5000
);
--rollback ALTER TABLE rr_event RESET (fillfactor, autovacuum_vacuum_insert_scale_factor, autovacuum_vacuum_insert_threshold);
