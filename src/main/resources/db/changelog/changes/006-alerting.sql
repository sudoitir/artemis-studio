--liquibase formatted sql

-- Alerting: rules evaluated against metric_sample, firing state per subject,
-- and the channels a firing is delivered to. Populated from Phase 7.

--changeset artemis-studio:006-alert-rule
CREATE TABLE alert_rule (
    threshold    DOUBLE PRECISION NOT NULL,
    for_seconds  INTEGER NOT NULL DEFAULT 0,
    metric       TEXT NOT NULL,
    comparator   TEXT NOT NULL,
    severity     TEXT NOT NULL DEFAULT 'WARNING',
    scope        JSONB,                    -- {addressPattern, queuePattern, node}
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id   UUID,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_alert_rule PRIMARY KEY (id),
    CONSTRAINT fk_alert_rule_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_rule_comparator CHECK (comparator IN ('GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE')),
    CONSTRAINT ck_alert_rule_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);
--rollback DROP TABLE alert_rule;

--changeset artemis-studio:006-alert-state
CREATE TABLE alert_state (
    since             TIMESTAMPTZ,
    last_notified_at  TIMESTAMPTZ,
    last_value        DOUBLE PRECISION,
    subject_key       TEXT NOT NULL,       -- resolved node/address/queue this firing is about
    state             TEXT NOT NULL DEFAULT 'OK',
    rule_id           UUID NOT NULL,
    CONSTRAINT pk_alert_state PRIMARY KEY (rule_id, subject_key),
    CONSTRAINT fk_alert_state_rule FOREIGN KEY (rule_id)
        REFERENCES alert_rule (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_state CHECK (state IN ('OK', 'PENDING', 'FIRING'))
);
--rollback DROP TABLE alert_state;

--changeset artemis-studio:006-alert-state-autovacuum
--comment: one row per (rule, subject), updated on every evaluation tick.
ALTER TABLE alert_state SET (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.05
);
--rollback ALTER TABLE alert_state RESET (fillfactor, autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor);

--changeset artemis-studio:006-notification-channel
CREATE TABLE notification_channel (
    name    TEXT NOT NULL,
    kind    TEXT NOT NULL,                 -- WEBHOOK | SLACK | EMAIL
    config  JSONB NOT NULL,
    id      UUID NOT NULL DEFAULT gen_random_uuid(),
    CONSTRAINT pk_notification_channel PRIMARY KEY (id),
    CONSTRAINT ck_notification_channel_kind CHECK (kind IN ('WEBHOOK', 'SLACK', 'EMAIL'))
);
--rollback DROP TABLE notification_channel;
