--liquibase formatted sql

-- The installation-scoped AUDIT_DELIVERY state condition (ADR-0190): an audit receiver that lost
-- records to retention or is far behind. The state-condition check gains it, and one enabled rule
-- is seeded, bound to no channel, the way the storage rules are (0005). An operator's deletion is
-- never undone.
-- Never edit this file once released.

--changeset artemis-studio:feature-alerting-0008-audit-delivery-rule splitStatements:true
ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_state_condition
        CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text, 'STORAGE_QUOTA'::text, 'STORAGE_HEALTH'::text, 'AUDIT_DELIVERY'::text]))));
INSERT INTO alert_rule (name, kind, state_condition, for_seconds, severity)
SELECT 'Audit delivery', 'STATE', 'AUDIT_DELIVERY', 0, 'WARNING'
WHERE NOT EXISTS (
    SELECT 1 FROM alert_rule r WHERE r.cluster_id IS NULL AND r.state_condition = 'AUDIT_DELIVERY');
--rollback DELETE FROM alert_rule WHERE cluster_id IS NULL AND state_condition = 'AUDIT_DELIVERY';
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text, 'STORAGE_QUOTA'::text, 'STORAGE_HEALTH'::text]))));
