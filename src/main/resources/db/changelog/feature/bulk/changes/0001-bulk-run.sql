--liquibase formatted sql

-- Bulk runs (ADR-0093): one queue operation over a set of queues frozen at preview, and
-- one row per queue in it. Both are updated as the run works through its queues, so both
-- carry fillfactor and autovacuum parameters. At most one run executes on a cluster at a
-- time, which the partial unique index enforces rather than a check-then-act in code.
-- audit_event_id names the run's audit event without a foreign key: audit outlives what
-- it names. Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-bulk-0001-bulk-run splitStatements:true
CREATE TABLE bulk_run (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    estimate bigint,
    audit_event_id bigint,
    total_items integer NOT NULL,
    succeeded integer DEFAULT 0 NOT NULL,
    failed integer DEFAULT 0 NOT NULL,
    skipped integer DEFAULT 0 NOT NULL,
    operation text NOT NULL,
    status text NOT NULL,
    username text NOT NULL,
    plan_hash text NOT NULL,
    error text,
    selection jsonb NOT NULL,
    options jsonb NOT NULL,
    id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    estimate_complete boolean NOT NULL,
    override_cap boolean DEFAULT false NOT NULL,
    continue_on_failure boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_bulk_run_operation CHECK (operation IN ('PAUSE', 'RESUME', 'PURGE', 'DELETE')),
    CONSTRAINT ck_bulk_run_status CHECK (status IN (
        'PREVIEWED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'STOPPED', 'INTERRUPTED'))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE bulk_run_item (
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    affected bigint,
    ordinal integer NOT NULL,
    queue_name text NOT NULL,
    status text NOT NULL,
    error text,
    estimate jsonb NOT NULL,
    outcome jsonb,
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    CONSTRAINT ck_bulk_run_item_status CHECK (status IN (
        'PENDING', 'REFUSED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'CANCELLED', 'UNKNOWN'))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY bulk_run
    ADD CONSTRAINT pk_bulk_run PRIMARY KEY (id);

ALTER TABLE ONLY bulk_run_item
    ADD CONSTRAINT pk_bulk_run_item PRIMARY KEY (id);

CREATE UNIQUE INDEX ux_bulk_run_running ON bulk_run USING btree (cluster_id) WHERE (status = 'RUNNING');

CREATE INDEX ix_bulk_run_cluster ON bulk_run USING btree (cluster_id, created_at DESC);

CREATE INDEX ix_bulk_run_preview_expiry ON bulk_run USING btree (expires_at) WHERE (status = 'PREVIEWED');

CREATE UNIQUE INDEX ux_bulk_run_item_ordinal ON bulk_run_item USING btree (run_id, ordinal);

ALTER TABLE ONLY bulk_run
    ADD CONSTRAINT fk_bulk_run_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY bulk_run_item
    ADD CONSTRAINT fk_bulk_run_item_run FOREIGN KEY (run_id) REFERENCES bulk_run(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS bulk_run_item;
--rollback DROP TABLE IF EXISTS bulk_run;
