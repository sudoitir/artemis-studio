--liquibase formatted sql

-- How many messages a registration handles at once on each node (ADR-0112): 1 to 32 for a consumer,
-- exactly 1 for a tap. Existing registrations keep the one consumer per node they had. Never edit
-- this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-plugins-0002-plugin-message-concurrency
ALTER TABLE plugin_message_registration ADD COLUMN concurrency integer DEFAULT 1 NOT NULL;
ALTER TABLE plugin_message_registration
    ADD CONSTRAINT ck_plugin_message_registration_concurrency
        CHECK (((mode = 'TAP'::text) AND (concurrency = 1)) OR ((mode = 'CONSUME'::text) AND (concurrency >= 1) AND (concurrency <= 32)));
--rollback ALTER TABLE plugin_message_registration DROP CONSTRAINT IF EXISTS ck_plugin_message_registration_concurrency;
--rollback ALTER TABLE plugin_message_registration DROP COLUMN IF EXISTS concurrency;
