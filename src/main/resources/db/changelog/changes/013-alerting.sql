--liquibase formatted sql

-- Phase 7 gaps in the Phase 1 alerting schema (006-alerting.sql, released — never
-- edited). alert_rule modeled every rule as a metric threshold, but split-brain,
-- node-down, and replication-desync are state transitions with no metric_sample
-- row to compare against; there was no rule-to-channel routing, no firing
-- history (alert_state is overwritten in place), no delivery ledger, and
-- notification_channel had no secret encryption path. See ADR-0035, ADR-0036.

--changeset artemis-studio:013-alert-rule-kind
--comment: discriminates a metric-threshold rule (existing columns) from a state-
--         condition rule (state_condition), sharing one evaluator and state
--         machine (ADR-0035). Existing rows, if any, default to METRIC_THRESHOLD.
ALTER TABLE alert_rule
    ADD COLUMN created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN name            TEXT NOT NULL DEFAULT '',
    ADD COLUMN state_condition TEXT,
    ADD COLUMN kind            TEXT NOT NULL DEFAULT 'METRIC_THRESHOLD';

ALTER TABLE alert_rule
    ALTER COLUMN metric DROP NOT NULL,
    ALTER COLUMN comparator DROP NOT NULL,
    ALTER COLUMN threshold DROP NOT NULL;

ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_kind CHECK (kind IN ('METRIC_THRESHOLD', 'STATE'));

ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition
    CHECK (state_condition IS NULL
        OR state_condition IN ('SPLIT_BRAIN', 'NODE_DOWN', 'REPLICATION_BEHIND', 'CLUSTER_DEGRADED'));

ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_kind_shape CHECK (
    (kind = 'METRIC_THRESHOLD' AND metric IS NOT NULL AND comparator IS NOT NULL AND threshold IS NOT NULL
        AND state_condition IS NULL)
    OR
    (kind = 'STATE' AND state_condition IS NOT NULL AND metric IS NULL AND comparator IS NULL AND threshold IS NULL)
);
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_kind_shape;
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_kind;
--rollback ALTER TABLE alert_rule ALTER COLUMN threshold SET NOT NULL;
--rollback ALTER TABLE alert_rule ALTER COLUMN comparator SET NOT NULL;
--rollback ALTER TABLE alert_rule ALTER COLUMN metric SET NOT NULL;
--rollback ALTER TABLE alert_rule DROP COLUMN kind;
--rollback ALTER TABLE alert_rule DROP COLUMN state_condition;
--rollback ALTER TABLE alert_rule DROP COLUMN name;
--rollback ALTER TABLE alert_rule DROP COLUMN updated_at;
--rollback ALTER TABLE alert_rule DROP COLUMN created_at;

--changeset artemis-studio:013-alert-rule-index
--comment: the evaluator's only rule query — enabled rules of one kind for one cluster.
CREATE INDEX ix_alert_rule_cluster_kind ON alert_rule (cluster_id, kind) WHERE enabled;
--rollback DROP INDEX ix_alert_rule_cluster_kind;

--changeset artemis-studio:013-alert-rule-channel
--comment: which channels a rule's firings deliver to; unmodeled in 006.
CREATE TABLE alert_rule_channel (
    rule_id    UUID NOT NULL,
    channel_id UUID NOT NULL,
    CONSTRAINT pk_alert_rule_channel PRIMARY KEY (rule_id, channel_id),
    CONSTRAINT fk_alert_rule_channel_rule FOREIGN KEY (rule_id)
        REFERENCES alert_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_rule_channel_channel FOREIGN KEY (channel_id)
        REFERENCES notification_channel (id) ON DELETE CASCADE
);
--rollback DROP TABLE alert_rule_channel;

--changeset artemis-studio:013-alert-firing
--comment: append-only firing/resolve history — alert_state alone is overwritten in
--         place and cannot answer "when did this fire and when did it clear"
--         (same append-only shape as broker_event, changeset 010).
CREATE TABLE alert_firing (
    started_at   TIMESTAMPTZ NOT NULL,
    resolved_at  TIMESTAMPTZ,
    value        DOUBLE PRECISION,
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    subject_key  TEXT NOT NULL,
    severity     TEXT NOT NULL,
    rule_id      UUID NOT NULL,
    cluster_id   UUID NOT NULL,
    CONSTRAINT pk_alert_firing PRIMARY KEY (seq),
    CONSTRAINT fk_alert_firing_rule FOREIGN KEY (rule_id)
        REFERENCES alert_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_firing_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE
);

CREATE INDEX ix_alert_firing_cluster_seq ON alert_firing (cluster_id, seq DESC);
--comment: the currently-firing count read (the shell badge and the topology dots).
CREATE INDEX ix_alert_firing_open ON alert_firing (cluster_id) WHERE resolved_at IS NULL;
--rollback DROP TABLE alert_firing;

--changeset artemis-studio:013-alert-firing-autovacuum
--comment: append-only, high churn on start/resolve — same tuning as broker_event.
ALTER TABLE alert_firing SET (
    fillfactor = 100,
    autovacuum_vacuum_insert_scale_factor = 0.0,
    autovacuum_vacuum_insert_threshold = 5000
);
--rollback ALTER TABLE alert_firing RESET (fillfactor, autovacuum_vacuum_insert_scale_factor, autovacuum_vacuum_insert_threshold);

--changeset artemis-studio:013-alert-delivery
--comment: durable retry queue and delivery ledger, batched one row per (rule,
--         channel) per evaluation tick (ADR-0036) — never one row per subject.
CREATE TABLE alert_delivery (
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,
    attempts         INTEGER NOT NULL DEFAULT 0,
    payload          JSONB NOT NULL,
    last_error       TEXT,
    state            TEXT NOT NULL DEFAULT 'PENDING',
    rule_id          UUID NOT NULL,
    channel_id       UUID NOT NULL,
    CONSTRAINT pk_alert_delivery PRIMARY KEY (seq),
    CONSTRAINT fk_alert_delivery_rule FOREIGN KEY (rule_id)
        REFERENCES alert_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_delivery_channel FOREIGN KEY (channel_id)
        REFERENCES notification_channel (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_delivery_state CHECK (state IN ('PENDING', 'SENT', 'FAILED', 'DEAD'))
);

--comment: the dispatcher's only query — due, not-yet-terminal deliveries.
CREATE INDEX ix_alert_delivery_due ON alert_delivery (next_attempt_at) WHERE state = 'PENDING';
--rollback DROP TABLE alert_delivery;

--changeset artemis-studio:013-alert-delivery-autovacuum
--comment: one row per (rule,channel) per tick, updated by the dispatcher on every
--         attempt — same per-tick-update tuning as alert_state.
ALTER TABLE alert_delivery SET (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.05
);
--rollback ALTER TABLE alert_delivery RESET (fillfactor, autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor);

--changeset artemis-studio:013-notification-channel-secret
--comment: a channel's secret (Slack webhook URL, webhook signing secret) is the
--         credential — AES-GCM at rest (ADR-0009), never plaintext config
--         (ADR-0036). EMAIL is dropped: no SMTP is built in Phase 7.
ALTER TABLE notification_channel
    ADD COLUMN secret_ct    BYTEA,
    ADD COLUMN secret_nonce BYTEA,
    ADD COLUMN enabled      BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE notification_channel ADD CONSTRAINT uq_notification_channel_name UNIQUE (name);

ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_kind;
ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_kind CHECK (kind IN ('WEBHOOK', 'SLACK'));
--rollback ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_kind CHECK (kind IN ('WEBHOOK', 'SLACK', 'EMAIL'));
--rollback ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_kind;
--rollback ALTER TABLE notification_channel DROP CONSTRAINT uq_notification_channel_name;
--rollback ALTER TABLE notification_channel DROP COLUMN enabled;
--rollback ALTER TABLE notification_channel DROP COLUMN secret_nonce;
--rollback ALTER TABLE notification_channel DROP COLUMN secret_ct;

--changeset artemis-studio:013-builtin-rules-backfill
--comment: seed the three built-in state-condition rules for every cluster that
--         already exists; ClusterService.register seeds new ones going forward.
--         Ordinary, editable, unrouted rows (design.md decision 8) — not an
--         unconditional check outside the rule model.
INSERT INTO alert_rule (kind, state_condition, name, for_seconds, severity, cluster_id, enabled)
SELECT 'STATE', 'SPLIT_BRAIN', 'Split-brain', 0, 'CRITICAL', c.id, TRUE FROM cluster c;
INSERT INTO alert_rule (kind, state_condition, name, for_seconds, severity, cluster_id, enabled)
SELECT 'STATE', 'NODE_DOWN', 'Node down', 30, 'CRITICAL', c.id, TRUE FROM cluster c;
INSERT INTO alert_rule (kind, state_condition, name, for_seconds, severity, cluster_id, enabled)
SELECT 'STATE', 'REPLICATION_BEHIND', 'Replication behind', 120, 'WARNING', c.id, TRUE FROM cluster c;
--rollback DELETE FROM alert_rule WHERE kind = 'STATE' AND name IN ('Split-brain', 'Node down', 'Replication behind');
