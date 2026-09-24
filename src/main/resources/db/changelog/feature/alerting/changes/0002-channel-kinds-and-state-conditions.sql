--liquibase formatted sql

-- Email, Microsoft Teams and PagerDuty channels (ADR-0105). Only the kind check is widened:
-- each kind's non-secret settings live in the existing config jsonb and its secret (SMTP
-- password, Teams webhook URL, PagerDuty routing key) in the existing sealed columns.
--
-- The state-condition check is widened too. It still listed only the five conditions the
-- baseline shipped with, so a CONFIG_DRIFT rule (ADR-0067 D8) was refused by the database
-- although the API accepted it; SETUP_RISK (ADR-0106) is added beside it.
-- Never edit this file once released.

--changeset artemis-studio:feature-alerting-0002-channel-kinds-and-state-conditions splitStatements:true
ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_kind;
ALTER TABLE notification_channel
    ADD CONSTRAINT ck_notification_channel_kind
        CHECK ((kind = ANY (ARRAY['WEBHOOK'::text, 'SLACK'::text, 'EMAIL'::text, 'TEAMS'::text, 'PAGERDUTY'::text])));
ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_state_condition
        CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text]))));
CREATE INDEX ix_alert_delivery_channel_seq ON alert_delivery USING btree (channel_id, seq DESC);
--rollback DROP INDEX IF EXISTS ix_alert_delivery_channel_seq;
--rollback DELETE FROM alert_rule WHERE state_condition IN ('CONFIG_DRIFT', 'SETUP_RISK');
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text]))));
--rollback DELETE FROM notification_channel WHERE kind IN ('EMAIL', 'TEAMS', 'PAGERDUTY');
--rollback ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_kind;
--rollback ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_kind CHECK ((kind = ANY (ARRAY['WEBHOOK'::text, 'SLACK'::text])));
