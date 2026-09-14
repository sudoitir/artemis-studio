--liquibase formatted sql

-- The content policy's at-rest columns on message_index (ADR-0075 D4): the policy version a row was masked
-- under, and the sealed originals of its masked values. Appended rather than placed by the padding rule
-- (non-negotiable #7): a column cannot be inserted into an existing table, and message_index is too large to
-- rewrite for three columns. Existing rows get version 0, so the re-mask job governs them after the upgrade.
-- Never edit this file once released.

--changeset artemis-studio:feature-sql-0002-governance-sealing splitStatements:true
ALTER TABLE message_index ADD COLUMN policy_version integer DEFAULT 0 NOT NULL;
ALTER TABLE message_index ADD COLUMN sealed bytea;
ALTER TABLE message_index ADD COLUMN sealed_nonce bytea;

-- The re-mask job and its progress count both look for rows below the current version.
CREATE INDEX ix_message_index_policy_version ON message_index (policy_version);

--rollback DROP INDEX IF EXISTS ix_message_index_policy_version;
--rollback ALTER TABLE message_index DROP COLUMN IF EXISTS sealed_nonce;
--rollback ALTER TABLE message_index DROP COLUMN IF EXISTS sealed;
--rollback ALTER TABLE message_index DROP COLUMN IF EXISTS policy_version;
