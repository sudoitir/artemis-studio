--liquibase formatted sql

-- plugin_secret holds one envelope-encrypted blob (ADR-0132 D2) instead of a ciphertext and nonce. Existing
-- rows cannot be converted (no data migration before the stable release), so they are deleted and plugins'
-- secrets must be set again.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0004-envelope-plugin-secret
DELETE FROM plugin_secret;
ALTER TABLE plugin_secret DROP COLUMN ciphertext;
ALTER TABLE plugin_secret DROP COLUMN nonce;
ALTER TABLE plugin_secret ADD COLUMN sealed bytea NOT NULL;
--rollback DELETE FROM plugin_secret;
--rollback ALTER TABLE plugin_secret DROP COLUMN sealed;
--rollback ALTER TABLE plugin_secret ADD COLUMN ciphertext bytea NOT NULL;
--rollback ALTER TABLE plugin_secret ADD COLUMN nonce bytea NOT NULL;
