--liquibase formatted sql

-- A bytes message whose body is text is stored as that text (ADR-0148), so "binary" is a property of the
-- stored body, not of the message type. body_base64 marks a genuinely binary body (withheld when stored,
-- its original sealed), body_compression the gzip or deflate a text body was unwrapped from. Every type-4
-- row stored before this change was base64, so it is marked so and the remasker keeps it withheld. The
-- full-text index then covers every text body, whatever the message type.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-sql-0005-body-encoding
ALTER TABLE message_index ADD COLUMN body_compression text;
ALTER TABLE message_index ADD COLUMN body_base64 boolean DEFAULT false NOT NULL;
UPDATE message_index SET body_base64 = true WHERE message_type = 4;
DROP INDEX ix_message_index_body_fts;
CREATE INDEX ix_message_index_body_fts ON message_index USING gin (to_tsvector('simple'::regconfig, body))
    WHERE body IS NOT NULL AND NOT body_base64;
--rollback DROP INDEX ix_message_index_body_fts;
--rollback CREATE INDEX ix_message_index_body_fts ON message_index USING gin (to_tsvector('simple'::regconfig, body)) WHERE body IS NOT NULL AND message_type <> 4;
--rollback ALTER TABLE message_index DROP COLUMN body_base64;
--rollback ALTER TABLE message_index DROP COLUMN body_compression;
