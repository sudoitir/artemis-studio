--liquibase formatted sql

-- The platform/clusters module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:platform-clusters-0001-baseline splitStatements:true
CREATE TABLE broker_credential (
    kind text NOT NULL,
    username text,
    secret_ct bytea NOT NULL,
    secret_nonce bytea NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL
);

CREATE TABLE broker_node (
    last_seen_at timestamp with time zone,
    artemis_node_id text,
    name text NOT NULL,
    jolokia_url text,
    core_url text,
    ha_role text DEFAULT 'STANDALONE'::text NOT NULL,
    pair_group text,
    state text DEFAULT 'UNKNOWN'::text NOT NULL,
    version text,
    last_error text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    discovered boolean DEFAULT false NOT NULL,
    manual_override boolean DEFAULT false NOT NULL,
    observed_cycle bigint,
    active boolean,
    replica_sync boolean,
    clock_measured_at timestamp with time zone,
    clock_offset_ms bigint,
    clock_uncertainty_ms integer,
    CONSTRAINT ck_broker_node_ha_role CHECK ((ha_role = ANY (ARRAY['PRIMARY'::text, 'BACKUP'::text, 'STANDALONE'::text])))
);

CREATE TABLE broker_tls (
    truststore_ref text,
    client_cert_ref text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    verify_hostname boolean DEFAULT true NOT NULL
);

CREATE TABLE cluster (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    name text NOT NULL,
    description text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    environment_id uuid,
    read_only boolean DEFAULT false NOT NULL,
    management_write_observed_at timestamp with time zone,
    management_write_status text,
    management_write_reason text,
    CONSTRAINT ck_cluster_management_write_status CHECK (((management_write_status IS NULL) OR (management_write_status = ANY (ARRAY['AVAILABLE'::text, 'UNAVAILABLE'::text]))))
);

CREATE TABLE environment (
    sort_order integer DEFAULT 0 NOT NULL,
    name text NOT NULL,
    colour text,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT pk_broker_credential PRIMARY KEY (id);

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT pk_broker_node PRIMARY KEY (id);

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT pk_broker_tls PRIMARY KEY (id);

ALTER TABLE ONLY cluster
    ADD CONSTRAINT pk_cluster PRIMARY KEY (id);

ALTER TABLE ONLY environment
    ADD CONSTRAINT pk_environment PRIMARY KEY (id);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT uq_broker_credential_cluster_kind UNIQUE (cluster_id, kind);

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT uq_broker_node_cluster_name UNIQUE (cluster_id, name);

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT uq_broker_tls_cluster UNIQUE (cluster_id);

ALTER TABLE ONLY cluster
    ADD CONSTRAINT uq_cluster_env_name UNIQUE (environment_id, name);

ALTER TABLE ONLY environment
    ADD CONSTRAINT uq_environment_name UNIQUE (name);

CREATE INDEX ix_broker_node_cluster ON broker_node USING btree (cluster_id);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT fk_broker_credential_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT fk_broker_node_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT fk_broker_tls_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY cluster
    ADD CONSTRAINT fk_cluster_environment FOREIGN KEY (environment_id) REFERENCES environment(id) ON DELETE SET NULL;

COMMENT ON COLUMN broker_node.clock_offset_ms IS 'How far ahead of Studio this broker''s clock is, in milliseconds; negative means behind. Measured from the Jolokia response timestamp (ADR-0053). NULL means never measured, not zero.';

COMMENT ON COLUMN broker_node.clock_uncertainty_ms IS 'Half the best round trip plus Jolokia''s second granularity. An offset inside this band is indistinguishable from agreement and is never reported as skew.';

COMMENT ON COLUMN cluster.management_write_status IS 'Evidence of an attempted management write (ADR-0049 D5): AVAILABLE once one has succeeded, UNAVAILABLE once one has been refused for an authorization reason, NULL until one is attempted. A write that fails for any other reason must not set this.';
--rollback DROP TABLE IF EXISTS broker_tls CASCADE;
--rollback DROP TABLE IF EXISTS broker_credential CASCADE;
--rollback DROP TABLE IF EXISTS broker_node CASCADE;
--rollback DROP TABLE IF EXISTS cluster CASCADE;
--rollback DROP TABLE IF EXISTS environment CASCADE;
