--liquibase formatted sql

-- message_index keeps one envelope-encrypted blob in sealed (ADR-0132 D2); sealed_nonce goes. Dropping and adding
-- sealed on the partitioned table changes only the catalog of the table and its partitions, and no row is
-- rewritten, so the sealed originals are gone and the rows stay masked (no data migration before the stable
-- release).
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-sql-0004-envelope-sealed
ALTER TABLE message_index DROP COLUMN sealed;
ALTER TABLE message_index DROP COLUMN sealed_nonce;
ALTER TABLE message_index ADD COLUMN sealed bytea;
--rollback ALTER TABLE message_index DROP COLUMN sealed;
--rollback ALTER TABLE message_index ADD COLUMN sealed bytea;
--rollback ALTER TABLE message_index ADD COLUMN sealed_nonce bytea;
