--liquibase formatted sql

-- Where each audit receiver (a plugin that declares audit.receive) has got to. Studio keeps the
-- cursor, so retention knows what was acknowledged: the position of the last record the receiver
-- returned from, when, and the backoff after failures. A receiver starts at the beginning of the
-- retained trail. The row goes when the plugin is uninstalled, never when it is only disabled.
-- Never edit this file once released.

--changeset artemis-studio:kernel-auditfeed-0001-audit-receiver splitStatements:false
CREATE TABLE audit_receiver (
    acked_at        timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    pos_txid        xid8        NOT NULL DEFAULT '0'::text::xid8,
    pos_seq         bigint      NOT NULL DEFAULT 0,
    failures        integer     NOT NULL DEFAULT 0,
    plugin_id       text        NOT NULL,
    last_error      text,
    CONSTRAINT pk_audit_receiver PRIMARY KEY (plugin_id)
);

COMMENT ON TABLE audit_receiver IS 'One row per audit receiver plugin: the position it has acknowledged and its retry backoff.';

-- Records retention removed before a receiver acknowledged them. audited_at is set when the
-- AUDIT_GAP event was written, delivered_at when the receiver was told.
CREATE TABLE audit_receiver_gap (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    count        bigint      NOT NULL,
    first_ts     timestamptz NOT NULL,
    last_ts      timestamptz NOT NULL,
    audited_at   timestamptz,
    delivered_at timestamptz,
    plugin_id    text        NOT NULL,
    CONSTRAINT pk_audit_receiver_gap PRIMARY KEY (id),
    CONSTRAINT fk_audit_receiver_gap_receiver FOREIGN KEY (plugin_id) REFERENCES audit_receiver (plugin_id) ON DELETE CASCADE
);

CREATE INDEX ix_audit_receiver_gap_plugin ON audit_receiver_gap (plugin_id);

-- The time from which the audit trail is complete, once retention has removed records. One row.
CREATE TABLE audit_feed_state (
    retained_from timestamptz,
    only_row      boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_audit_feed_state PRIMARY KEY (only_row),
    CONSTRAINT ck_audit_feed_state_single CHECK (only_row)
);

INSERT INTO audit_feed_state (retained_from) VALUES (NULL);
