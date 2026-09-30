--liquibase formatted sql

-- broker_credential holds one envelope-encrypted blob (ADR-0132 D2) instead of a ciphertext and nonce.
-- Existing rows cannot be converted (no data migration before the stable release), so they are deleted and
-- cluster and bridge credentials must be entered again.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:platform-clusters-0002-envelope-broker-credential
DELETE FROM broker_credential;
ALTER TABLE broker_credential DROP COLUMN secret_ct;
ALTER TABLE broker_credential DROP COLUMN secret_nonce;
ALTER TABLE broker_credential ADD COLUMN sealed bytea NOT NULL;
--rollback DELETE FROM broker_credential;
--rollback ALTER TABLE broker_credential DROP COLUMN sealed;
--rollback ALTER TABLE broker_credential ADD COLUMN secret_ct bytea NOT NULL;
--rollback ALTER TABLE broker_credential ADD COLUMN secret_nonce bytea NOT NULL;
