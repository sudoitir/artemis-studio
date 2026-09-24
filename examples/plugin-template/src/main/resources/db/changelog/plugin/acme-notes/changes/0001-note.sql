--liquibase formatted sql

-- A note on a queue. cluster_id is a plain uuid, never a foreign key into Studio's tables:
-- a plugin's schema may not constrain Studio's (activation refuses it).
--changeset acme:0001-note
CREATE TABLE note (
    created_at timestamp with time zone NOT NULL,
    cluster_id uuid NOT NULL,
    id uuid NOT NULL PRIMARY KEY,
    queue text NOT NULL,
    author text NOT NULL,
    body text NOT NULL
);
CREATE INDEX ix_note_queue ON note (cluster_id, queue, created_at DESC);
--rollback DROP TABLE note;
