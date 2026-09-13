--liquibase formatted sql

-- The kernel/audit module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-audit-0001-baseline splitStatements:true
CREATE TABLE audit_event (
    id bigint NOT NULL,
    ts timestamp with time zone DEFAULT now() NOT NULL,
    affected_count bigint,
    action text NOT NULL,
    target_type text,
    target_name text,
    username text,
    outcome text DEFAULT 'PENDING'::text NOT NULL,
    error text,
    request_id text,
    params jsonb,
    source_ip inet,
    user_id uuid,
    cluster_id uuid,
    node_id uuid,
    dry_run boolean DEFAULT false NOT NULL,
    outcome_detail jsonb,
    CONSTRAINT ck_audit_event_outcome CHECK ((outcome = ANY (ARRAY['PENDING'::text, 'SUCCESS'::text, 'FAILURE'::text])))
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000', autovacuum_analyze_scale_factor='0.02');

ALTER TABLE audit_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT pk_audit_event PRIMARY KEY (id);

CREATE INDEX ix_audit_event_cluster ON audit_event USING btree (cluster_id, ts DESC);

CREATE INDEX ix_audit_event_ts ON audit_event USING btree (ts DESC);

CREATE INDEX ix_audit_event_user ON audit_event USING btree (user_id, ts DESC);

COMMENT ON COLUMN audit_event.outcome_detail IS 'Per-node outcome of a cluster-wide fan-out command (ADR-0049 D2/D4): a JSON array of {nodeId, nodeName, status, affected, error}. Null for single-node actions.';
--rollback DROP TABLE IF EXISTS audit_event CASCADE;
