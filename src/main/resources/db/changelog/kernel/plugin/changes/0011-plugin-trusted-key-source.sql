--liquibase formatted sql

-- Where a trusted publisher key came from: an administrator added it in the UI or API (ADMIN), or the
-- configuration pins it (CONFIGURATION). Studio reconciles the configured keys with this table at
-- every start, so a CONFIGURATION row is removed once its key is no longer configured, and the API
-- refuses to remove one. Existing rows were all added by administrators. Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0011-plugin-trusted-key-source
ALTER TABLE plugin_trusted_key ADD COLUMN source text NOT NULL DEFAULT 'ADMIN';
ALTER TABLE plugin_trusted_key
    ADD CONSTRAINT ck_plugin_trusted_key_source CHECK (source IN ('ADMIN', 'CONFIGURATION'));
--rollback ALTER TABLE plugin_trusted_key DROP CONSTRAINT IF EXISTS ck_plugin_trusted_key_source;
--rollback ALTER TABLE plugin_trusted_key DROP COLUMN IF EXISTS source;
