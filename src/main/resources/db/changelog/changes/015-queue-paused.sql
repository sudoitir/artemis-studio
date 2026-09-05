--liquibase formatted sql

-- v1.0 slow-consumer detection (ADR-0044). A paused queue with a backlog and
-- attached consumers is *correctly* slow by every measure Studio can take, and
-- operationally expected — so the derived slow-consumer rule has to be able to
-- exclude it. Every `listQueues` row already carries `paused`, so this costs no
-- extra broker call, only the column to land it in.
-- `005-broker-cache.sql` is released and is never edited.

--changeset artemis-studio:015-queue-snapshot-paused
--comment: BOOLEAN joins the existing 1-byte tail (durable) rather than splitting
--         a wider alignment group; DEFAULT FALSE so the backfill needs no rewrite
--         of a table that is a disposable cache anyway.
ALTER TABLE queue_snapshot ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE queue_snapshot DROP COLUMN paused;
