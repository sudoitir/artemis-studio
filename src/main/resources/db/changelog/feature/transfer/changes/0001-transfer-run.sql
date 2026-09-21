--liquibase formatted sql

-- Cross-broker transfer runs (ADR-0097, transfer design D5). A run's messages are on the
-- brokers, never here: a move's are in its staging queue, so this table holds only the run's
-- bookkeeping, updated once per batch (hence fillfactor and autovacuum). Source and target are
-- the cluster plus the broker's own node id, shared by a live/backup pair, so a failover
-- continues on the new live node. At most one run is active per source queue, which the
-- partial unique index enforces. audit_event_id and target_audit_event_id name the current
-- segment's audit events without a foreign key: audit outlives what it names.
--
-- transfer_copied is a copy's ledger: the source ids it has copied, so a resumed copy does not
-- copy them again. Rows are inserted per batch and deleted when the run ends.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-transfer-0001-transfer-run splitStatements:true
CREATE TABLE transfer_run (
    t0 timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    updated_at timestamp with time zone,
    finished_at timestamp with time zone,
    estimate bigint,
    estimate_bytes bigint,
    staged bigint DEFAULT 0 NOT NULL,
    delivered bigint DEFAULT 0 NOT NULL,
    not_transferred bigint DEFAULT 0 NOT NULL,
    expired bigint DEFAULT 0 NOT NULL,
    returned bigint DEFAULT 0 NOT NULL,
    bytes bigint DEFAULT 0 NOT NULL,
    audit_event_id bigint,
    target_audit_event_id bigint,
    id_cursor integer DEFAULT 0 NOT NULL,
    mode text NOT NULL,
    state text NOT NULL,
    source_queue text NOT NULL,
    source_address text NOT NULL,
    source_routing_type text NOT NULL,
    source_node_name text NOT NULL,
    source_artemis_node_id text NOT NULL,
    target_queue text NOT NULL,
    target_address text NOT NULL,
    target_routing_type text NOT NULL,
    target_node_name text NOT NULL,
    target_artemis_node_id text NOT NULL,
    plan_hash text NOT NULL,
    username text NOT NULL,
    last_error text,
    error_snippet text,
    selection jsonb NOT NULL,
    findings jsonb NOT NULL,
    id uuid NOT NULL,
    source_cluster_id uuid NOT NULL,
    source_node_id uuid NOT NULL,
    target_cluster_id uuid NOT NULL,
    target_node_id uuid NOT NULL,
    operator_id uuid,
    same_node boolean NOT NULL,
    override_cap boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_transfer_run_mode CHECK (mode IN ('MOVE', 'COPY')),
    CONSTRAINT ck_transfer_run_state CHECK (state IN (
        'PREVIEWED', 'RUNNING', 'WAITING_FOR_CAPACITY', 'RETURNING', 'SUCCEEDED', 'PARTIAL', 'STOPPED',
        'INTERRUPTED', 'FAILED', 'RETURNED'))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

CREATE TABLE transfer_copied (
    message_id bigint NOT NULL,
    run_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_scale_factor='0.01', autovacuum_analyze_scale_factor='0.01', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT pk_transfer_run PRIMARY KEY (id);

ALTER TABLE ONLY transfer_copied
    ADD CONSTRAINT pk_transfer_copied PRIMARY KEY (run_id, message_id);

CREATE UNIQUE INDEX ux_transfer_run_active ON transfer_run USING btree (source_cluster_id, source_queue)
    WHERE (state IN ('RUNNING', 'WAITING_FOR_CAPACITY', 'RETURNING'));

CREATE INDEX ix_transfer_run_source ON transfer_run USING btree (source_cluster_id, created_at DESC);

CREATE INDEX ix_transfer_run_target ON transfer_run USING btree (target_cluster_id, created_at DESC);

CREATE INDEX ix_transfer_run_preview_expiry ON transfer_run USING btree (expires_at) WHERE (state = 'PREVIEWED');

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT fk_transfer_run_source_cluster FOREIGN KEY (source_cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT fk_transfer_run_target_cluster FOREIGN KEY (target_cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY transfer_copied
    ADD CONSTRAINT fk_transfer_copied_run FOREIGN KEY (run_id) REFERENCES transfer_run(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS transfer_copied;
--rollback DROP TABLE IF EXISTS transfer_run;
