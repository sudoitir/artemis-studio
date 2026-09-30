--liquibase formatted sql

-- notification_channel holds one envelope-encrypted blob (ADR-0132 D2) instead of a ciphertext and nonce.
-- Existing secrets cannot be converted (no data migration before the stable release), so they are dropped with
-- the old columns and each channel's secret must be entered again.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-alerting-0004-envelope-channel-secret
ALTER TABLE notification_channel DROP COLUMN secret_ct;
ALTER TABLE notification_channel DROP COLUMN secret_nonce;
ALTER TABLE notification_channel ADD COLUMN sealed bytea;
--rollback ALTER TABLE notification_channel DROP COLUMN sealed;
--rollback ALTER TABLE notification_channel ADD COLUMN secret_ct bytea;
--rollback ALTER TABLE notification_channel ADD COLUMN secret_nonce bytea;
