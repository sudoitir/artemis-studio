--liquibase formatted sql

-- The kernel/inbox module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-inbox-0001-baseline splitStatements:true
CREATE TABLE inbox_item (
    created_at timestamp with time zone NOT NULL,
    read_at timestamp with time zone,
    expires_at timestamp with time zone,
    id bigint NOT NULL,
    title text NOT NULL,
    body text,
    link text,
    dedupe_key text,
    data jsonb,
    source text NOT NULL,
    kind text NOT NULL,
    severity text NOT NULL,
    recipient_id uuid NOT NULL,
    CONSTRAINT ck_inbox_item_body CHECK ((char_length(body) <= 2000)),
    CONSTRAINT ck_inbox_item_data CHECK ((octet_length((data)::text) <= 4096)),
    CONSTRAINT ck_inbox_item_dedupe_key CHECK (((char_length(dedupe_key) >= 1) AND (char_length(dedupe_key) <= 200))),
    CONSTRAINT ck_inbox_item_link CHECK (((link IS NULL) OR ((char_length(link) <= 500) AND (link ~ '^/[A-Za-z0-9]'::text) AND (link !~ '//'::text) AND (link !~ '\\'::text)))),
    CONSTRAINT ck_inbox_item_severity CHECK ((severity = ANY (ARRAY['info'::text, 'success'::text, 'warning'::text, 'danger'::text]))),
    CONSTRAINT ck_inbox_item_title CHECK (((char_length(title) >= 1) AND (char_length(title) <= 200)))
)
WITH (fillfactor='90');

ALTER TABLE inbox_item ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME inbox_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY inbox_item
    ADD CONSTRAINT pk_inbox_item PRIMARY KEY (id);

CREATE INDEX ix_inbox_item_created ON inbox_item USING btree (created_at);

CREATE INDEX ix_inbox_item_expires ON inbox_item USING btree (expires_at) WHERE (expires_at IS NOT NULL);

CREATE INDEX ix_inbox_item_recipient ON inbox_item USING btree (recipient_id, id DESC);

CREATE INDEX ix_inbox_item_unread ON inbox_item USING btree (recipient_id) WHERE (read_at IS NULL);

CREATE UNIQUE INDEX ux_inbox_item_dedupe ON inbox_item USING btree (recipient_id, source, dedupe_key) WHERE (dedupe_key IS NOT NULL);

ALTER TABLE ONLY inbox_item
    ADD CONSTRAINT fk_inbox_item_recipient FOREIGN KEY (recipient_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS inbox_item CASCADE;
