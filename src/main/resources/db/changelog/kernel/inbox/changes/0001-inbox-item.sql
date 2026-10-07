--liquibase formatted sql

-- One notice for one recipient (change 18, design 10). Never edit this file once released; add a
-- new file beside it.

--changeset artemis-studio:kernel-inbox-0001-inbox-item
CREATE TABLE inbox_item (
    created_at timestamp with time zone NOT NULL,
    read_at timestamp with time zone,
    expires_at timestamp with time zone,
    id bigint GENERATED ALWAYS AS IDENTITY,
    title text NOT NULL,
    body text,
    link text,
    dedupe_key text,
    data json,
    source text NOT NULL,
    kind text NOT NULL,
    severity text NOT NULL,
    recipient_id uuid NOT NULL,
    CONSTRAINT pk_inbox_item PRIMARY KEY (id),
    CONSTRAINT fk_inbox_item_recipient FOREIGN KEY (recipient_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT ck_inbox_item_severity CHECK (severity IN ('info', 'success', 'warning', 'danger')),
    CONSTRAINT ck_inbox_item_title CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_inbox_item_body CHECK (char_length(body) <= 2000),
    CONSTRAINT ck_inbox_item_data CHECK (octet_length(data::text) <= 4096),
    CONSTRAINT ck_inbox_item_dedupe_key CHECK (char_length(dedupe_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_inbox_item_link CHECK (
        link IS NULL OR (char_length(link) <= 500 AND link ~ '^/[A-Za-z0-9]' AND link !~ '//' AND link !~ '\\')
    )
) WITH (fillfactor = 90);

CREATE UNIQUE INDEX ux_inbox_item_dedupe ON inbox_item (recipient_id, source, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX ix_inbox_item_recipient ON inbox_item (recipient_id, id DESC);
CREATE INDEX ix_inbox_item_unread ON inbox_item (recipient_id) WHERE read_at IS NULL;
CREATE INDEX ix_inbox_item_expires ON inbox_item (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX ix_inbox_item_created ON inbox_item (created_at);
