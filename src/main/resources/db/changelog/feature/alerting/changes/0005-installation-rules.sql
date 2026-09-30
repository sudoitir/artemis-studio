--liquibase formatted sql

-- Installation-scoped alert rules (ADR-0135). A rule with no cluster is about Studio itself, and so
-- are its state and its firings: alert_firing.cluster_id becomes nullable (alert_rule.cluster_id
-- already is, and alert_state and alert_delivery never had a cluster). The state-condition check
-- gains STORAGE_QUOTA and STORAGE_HEALTH, and one enabled rule of each is seeded, bound to no
-- channel. Seeding here rather than at startup makes it happen once per installation, before any
-- cluster exists, however many instances start together; an operator's deletion is never undone.
-- Never edit this file once released.

--changeset artemis-studio:feature-alerting-0005-installation-rules splitStatements:true
ALTER TABLE alert_firing ALTER COLUMN cluster_id DROP NOT NULL;
ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_state_condition
        CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text, 'STORAGE_QUOTA'::text, 'STORAGE_HEALTH'::text]))));
INSERT INTO alert_rule (name, kind, state_condition, for_seconds, severity) VALUES
    ('Storage quota', 'STATE', 'STORAGE_QUOTA', 0, 'WARNING'),
    ('Storage health', 'STATE', 'STORAGE_HEALTH', 0, 'WARNING');
--rollback DELETE FROM alert_rule WHERE cluster_id IS NULL AND state_condition IN ('STORAGE_QUOTA', 'STORAGE_HEALTH');
--rollback ALTER TABLE alert_firing ALTER COLUMN cluster_id SET NOT NULL;
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text]))));
