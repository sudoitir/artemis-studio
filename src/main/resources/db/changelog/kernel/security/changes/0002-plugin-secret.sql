--liquibase formatted sql

-- A runtime plugin's own secrets (ADR-0111): sealed by SecretVault with the AAD
-- "plugin|<plugin_id>|<name>", readable only through that plugin's PluginSecrets bean, and
-- deleted when the plugin is purged. No foreign key to plugin_install: a secret outlives an
-- uninstall until purge, as the plugin's schema does (ADR-0101).
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0002-plugin-secret
CREATE TABLE plugin_secret (
    updated_at timestamp with time zone NOT NULL,
    plugin_id text NOT NULL,
    name text NOT NULL,
    ciphertext bytea NOT NULL,
    nonce bytea NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);

ALTER TABLE ONLY plugin_secret
    ADD CONSTRAINT pk_plugin_secret PRIMARY KEY (id);

ALTER TABLE ONLY plugin_secret
    ADD CONSTRAINT uq_plugin_secret_name UNIQUE (plugin_id, name);
--rollback DROP TABLE IF EXISTS plugin_secret CASCADE;
