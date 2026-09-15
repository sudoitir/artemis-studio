--liquibase formatted sql

-- Routing the flow graph draws beyond produce/route/consume (ADR-0081): diverts, bridges,
-- store-and-forward queues between cluster nodes, temporary and filtered queues, and the
-- dead-letter / expiry addresses. Sampled in the same per-node request as client activity,
-- only while a cluster's flow is observed. A disposable cache, rewritten every sweep.

--changeset artemis-studio:feature-flow-0002-flow-route-cache splitStatements:true
CREATE TABLE flow_route (
    sampled_at timestamp with time zone NOT NULL,
    rate double precision,
    counter bigint DEFAULT 0 NOT NULL,
    kind text NOT NULL,
    name text NOT NULL,
    source text DEFAULT '' NOT NULL,
    target text DEFAULT '' NOT NULL,
    filter text,
    transformer text,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    exclusive boolean DEFAULT false NOT NULL,
    connected boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_flow_route_kind CHECK (kind IN (
        'DIVERT', 'BRIDGE', 'STORE_AND_FORWARD', 'TEMPORARY_QUEUE', 'QUEUE_FILTER', 'DEAD_LETTER', 'EXPIRY'))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

CREATE UNIQUE INDEX ux_flow_route_identity ON flow_route (node_id, kind, name, source, target);

CREATE INDEX ix_flow_route_cluster ON flow_route (cluster_id);

ALTER TABLE ONLY flow_route
    ADD CONSTRAINT fk_flow_route_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_route
    ADD CONSTRAINT fk_flow_route_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;
