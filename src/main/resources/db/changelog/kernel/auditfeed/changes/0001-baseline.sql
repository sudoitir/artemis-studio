--liquibase formatted sql

-- The kernel/auditfeed module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-auditfeed-0001-baseline splitStatements:true
CREATE TABLE audit_feed_state (
    retained_from timestamp with time zone,
    only_row boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_audit_feed_state_single CHECK (only_row)
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

CREATE TABLE audit_receiver (
    acked_at timestamp with time zone,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    pos_txid xid8 DEFAULT ('0'::text)::xid8 NOT NULL,
    pos_seq bigint DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    plugin_id text NOT NULL,
    last_error text
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE audit_receiver_gap (
    id bigint NOT NULL,
    count bigint NOT NULL,
    first_ts timestamp with time zone NOT NULL,
    last_ts timestamp with time zone NOT NULL,
    audited_at timestamp with time zone,
    delivered_at timestamp with time zone,
    plugin_id text NOT NULL
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

ALTER TABLE audit_receiver_gap ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME audit_receiver_gap_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY audit_feed_state
    ADD CONSTRAINT pk_audit_feed_state PRIMARY KEY (only_row);

ALTER TABLE ONLY audit_receiver
    ADD CONSTRAINT pk_audit_receiver PRIMARY KEY (plugin_id);

ALTER TABLE ONLY audit_receiver_gap
    ADD CONSTRAINT pk_audit_receiver_gap PRIMARY KEY (id);

CREATE INDEX ix_audit_receiver_gap_plugin ON audit_receiver_gap USING btree (plugin_id);

ALTER TABLE ONLY audit_receiver_gap
    ADD CONSTRAINT fk_audit_receiver_gap_receiver FOREIGN KEY (plugin_id) REFERENCES audit_receiver(plugin_id) ON DELETE CASCADE;

COMMENT ON TABLE audit_receiver IS 'One row per audit receiver plugin: the position it has acknowledged and its retry backoff.';

INSERT INTO audit_feed_state (retained_from) VALUES (NULL);
--rollback DROP TABLE IF EXISTS audit_receiver_gap CASCADE;
--rollback DROP TABLE IF EXISTS audit_receiver CASCADE;
--rollback DROP TABLE IF EXISTS audit_feed_state CASCADE;
