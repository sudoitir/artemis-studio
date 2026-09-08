--liquibase formatted sql

-- Full-text search over stored message bodies (ADR-0063).
--
-- A functional index, not a generated column: nothing is backfilled, the write path
-- does not change, and it cascades to every partition the maintainer creates.
--
-- `simple`, not `english`. Message bodies are identifiers, JSON and codes; English
-- stemming maps `orders` to `order` and leaves `4471` alone, which corrupts exactly
-- the lookups this exists for.

--changeset artemis-studio:023-message-index-fulltext
--comment: text-ish bodies only. to_tsvector over base64 produces garbage tokens and
--         bloats the index for no retrieval value, so a BytesMessage (core type 4,
--         stored base64) is excluded — and IndexQueryExecutor repeats that predicate
--         so Postgres can use the index rather than falling back to a sequential scan.
CREATE INDEX ix_message_index_body_fts
    ON message_index USING GIN (to_tsvector('simple', body))
    WHERE body IS NOT NULL AND message_type <> 4;
--rollback DROP INDEX IF EXISTS ix_message_index_body_fts;
