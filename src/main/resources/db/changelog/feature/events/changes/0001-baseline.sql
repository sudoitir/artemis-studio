--liquibase formatted sql

-- The feature/events module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-events-0001-baseline splitStatements:true
CREATE TABLE broker_event (
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint NOT NULL,
    type text NOT NULL,
    address text,
    routing_name text,
    consumer_name text,
    session_name text,
    connection_name text,
    remote_address text,
    username text,
    props jsonb,
    cluster_id uuid NOT NULL,
    node_id uuid
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000');

ALTER TABLE broker_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME broker_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY broker_event
    ADD CONSTRAINT pk_broker_event PRIMARY KEY (seq);

CREATE INDEX ix_broker_event_cluster_seq ON broker_event USING btree (cluster_id, seq DESC);

CREATE INDEX ix_broker_event_cluster_time ON broker_event USING btree (cluster_id, occurred_at DESC);

CREATE INDEX ix_broker_event_type ON broker_event USING btree (cluster_id, type, occurred_at DESC);

ALTER TABLE ONLY broker_event
    ADD CONSTRAINT fk_broker_event_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_event
    ADD CONSTRAINT fk_broker_event_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE SET NULL;
--rollback DROP TABLE IF EXISTS broker_event CASCADE;
