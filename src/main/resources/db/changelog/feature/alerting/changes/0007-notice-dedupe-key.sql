--liquibase formatted sql

-- A plugin that relays events keeps its own cursor in its own database, so it cannot commit the cursor and the
-- notice together. dedupe_key lets it queue the same notice again after a crash: for one source, a key is
-- queued once. Alerts and notices sent without a key leave it null and are never deduplicated.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-alerting-0007-notice-dedupe-key splitStatements:true
ALTER TABLE alert_delivery ADD COLUMN dedupe_key text;
CREATE UNIQUE INDEX ux_alert_delivery_source_dedupe ON alert_delivery USING btree (source, dedupe_key) WHERE (dedupe_key IS NOT NULL);
--rollback DROP INDEX IF EXISTS ux_alert_delivery_source_dedupe;
--rollback ALTER TABLE alert_delivery DROP COLUMN dedupe_key;
