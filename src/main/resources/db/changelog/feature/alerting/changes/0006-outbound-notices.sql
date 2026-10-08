--liquibase formatted sql

-- alert_delivery also queues notices that plugins send to a channel. A notice has no rule, so rule_id becomes
-- nullable, and kind says which one a row is. The check ties them together: an alert always has its rule,
-- a notice never does. source is the plugin that sent the notice; the index serves its hourly rate limit.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-alerting-0006-outbound-notices splitStatements:true
ALTER TABLE alert_delivery ADD COLUMN kind text DEFAULT 'alert' NOT NULL;
ALTER TABLE alert_delivery ADD COLUMN source text;
ALTER TABLE alert_delivery ALTER COLUMN rule_id DROP NOT NULL;
ALTER TABLE alert_delivery
    ADD CONSTRAINT ck_alert_delivery_kind CHECK ((kind = ANY (ARRAY['alert'::text, 'notice'::text])));
ALTER TABLE alert_delivery
    ADD CONSTRAINT ck_alert_delivery_rule CHECK (((kind = 'alert'::text) = (rule_id IS NOT NULL)));
CREATE INDEX ix_alert_delivery_source_created ON alert_delivery USING btree (source, created_at) WHERE (source IS NOT NULL);
--rollback DROP INDEX IF EXISTS ix_alert_delivery_source_created;
--rollback ALTER TABLE alert_delivery DROP CONSTRAINT ck_alert_delivery_rule;
--rollback ALTER TABLE alert_delivery DROP CONSTRAINT ck_alert_delivery_kind;
--rollback DELETE FROM alert_delivery WHERE rule_id IS NULL;
--rollback ALTER TABLE alert_delivery ALTER COLUMN rule_id SET NOT NULL;
--rollback ALTER TABLE alert_delivery DROP COLUMN source;
--rollback ALTER TABLE alert_delivery DROP COLUMN kind;
