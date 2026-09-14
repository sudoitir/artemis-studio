--liquibase formatted sql

-- Demand-driven client-activity sampling for the flow view (ADR-0081). All three tables are
-- disposable caches: a lease, the latest aggregated client edges, and each node's coverage.
-- Rewritten every sweep while a cluster is observed, so each is tuned for HOT updates and
-- aggressive vacuum (non-negotiable #7). Never edit this file once released.

--changeset artemis-studio:feature-flow-0001-flow-sampling-cache splitStatements:true
CREATE TABLE flow_demand (
    observed_until timestamp with time zone NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.1');

ALTER TABLE ONLY flow_demand
    ADD CONSTRAINT pk_flow_demand PRIMARY KEY (cluster_id);

ALTER TABLE ONLY flow_demand
    ADD CONSTRAINT fk_flow_demand_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

CREATE TABLE flow_client_edge (
    sampled_at timestamp with time zone NOT NULL,
    rate double precision,
    unacked bigint DEFAULT 0 NOT NULL,
    member_count integer NOT NULL,
    kind text NOT NULL,
    client_id text DEFAULT '' NOT NULL,
    user_name text DEFAULT '' NOT NULL,
    remote_host text DEFAULT '' NOT NULL,
    protocol text DEFAULT '' NOT NULL,
    address text DEFAULT '' NOT NULL,
    queue_name text DEFAULT '' NOT NULL,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    stalled boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_flow_client_edge_kind CHECK (kind IN ('PRODUCE', 'CONSUME'))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

CREATE UNIQUE INDEX ux_flow_client_edge_identity
    ON flow_client_edge (node_id, kind, client_id, user_name, remote_host, protocol, address, queue_name);

CREATE INDEX ix_flow_client_edge_cluster ON flow_client_edge (cluster_id);

ALTER TABLE ONLY flow_client_edge
    ADD CONSTRAINT fk_flow_client_edge_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_client_edge
    ADD CONSTRAINT fk_flow_client_edge_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

CREATE TABLE flow_node_sample (
    sampled_at timestamp with time zone NOT NULL,
    producers_seen integer DEFAULT 0 NOT NULL,
    producers_total integer DEFAULT 0 NOT NULL,
    consumers_seen integer DEFAULT 0 NOT NULL,
    consumers_total integer DEFAULT 0 NOT NULL,
    error text,
    error_kind text,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.1');

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT pk_flow_node_sample PRIMARY KEY (node_id);

CREATE INDEX ix_flow_node_sample_cluster ON flow_node_sample (cluster_id);

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT fk_flow_node_sample_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT fk_flow_node_sample_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;
