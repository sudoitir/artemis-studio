--liquibase formatted sql

-- The state-condition check still listed only the five conditions the baseline shipped with, so
-- a CONFIG_DRIFT rule (ADR-0067 D8) was refused by the database although the API accepted it.
-- Never edit this file once released.

--changeset artemis-studio:feature-alerting-0002-channel-kinds-and-state-conditions splitStatements:true
ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_state_condition
        CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text]))));
--rollback DELETE FROM alert_rule WHERE state_condition = 'CONFIG_DRIFT';
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text]))));
