--liquibase formatted sql

-- The feature/brokerconfig module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-brokerconfig-0001-baseline splitStatements:true
CREATE TABLE broker_config_apply (
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    id bigint NOT NULL,
    revision_id bigint NOT NULL,
    audit_event_id bigint,
    plan jsonb NOT NULL,
    outcome_detail jsonb,
    outcome text NOT NULL,
    summary text,
    actor text NOT NULL,
    cluster_id uuid NOT NULL,
    canary_node_id uuid,
    dry_run boolean NOT NULL,
    CONSTRAINT ck_broker_config_apply_outcome CHECK ((outcome = ANY (ARRAY['DRY_RUN'::text, 'APPLIED'::text, 'HALTED'::text, 'FAILED'::text])))
);

ALTER TABLE broker_config_apply ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME broker_config_apply_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE broker_config_declaration (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    current_revision_id bigint NOT NULL,
    apply_mode text DEFAULT 'STUDIO_MANAGED'::text NOT NULL,
    undeclared_exclusions jsonb DEFAULT '[]'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    report_undeclared boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_broker_config_declaration_mode CHECK ((apply_mode = ANY (ARRAY['STUDIO_MANAGED'::text, 'CONFIG_MANAGED'::text])))
);

CREATE TABLE broker_config_node_state (
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    verified_revision integer,
    findings jsonb DEFAULT '[]'::jsonb NOT NULL,
    state text NOT NULL,
    detail text,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    basis_ref bigint,
    basis text,
    CONSTRAINT ck_broker_config_node_state_basis CHECK (((basis IS NULL) OR (basis = ANY (ARRAY['VERIFIED_APPLY'::text, 'ADOPTED'::text, 'OBSERVED_MATCH'::text])))),
    CONSTRAINT ck_broker_config_node_state_state CHECK ((state = ANY (ARRAY['IN_SYNC'::text, 'DRIFTED'::text, 'NOT_EVALUATED'::text, 'UNREACHABLE'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE broker_config_owned_item (
    applied_at timestamp with time zone DEFAULT now() NOT NULL,
    revision_id bigint NOT NULL,
    kind text NOT NULL,
    item_key text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_broker_config_owned_item_kind CHECK ((kind = ANY (ARRAY['ADDRESS_SETTING'::text, 'SECURITY_SETTING'::text, 'DIVERT'::text])))
);

CREATE TABLE broker_config_revision (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    revision integer NOT NULL,
    document jsonb NOT NULL,
    source text NOT NULL,
    note text,
    created_by text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_broker_config_revision_source CHECK ((source = ANY (ARRAY['EDIT'::text, 'IMPORT_XML'::text, 'ADOPT'::text, 'MCP'::text, 'RECOMMENDED'::text])))
);

ALTER TABLE broker_config_revision ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME broker_config_revision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY broker_config_apply
    ADD CONSTRAINT pk_broker_config_apply PRIMARY KEY (id);

ALTER TABLE ONLY broker_config_declaration
    ADD CONSTRAINT pk_broker_config_declaration PRIMARY KEY (cluster_id);

ALTER TABLE ONLY broker_config_node_state
    ADD CONSTRAINT pk_broker_config_node_state PRIMARY KEY (cluster_id, node_id);

ALTER TABLE ONLY broker_config_owned_item
    ADD CONSTRAINT pk_broker_config_owned_item PRIMARY KEY (cluster_id, kind, item_key);

ALTER TABLE ONLY broker_config_revision
    ADD CONSTRAINT pk_broker_config_revision PRIMARY KEY (id);

ALTER TABLE ONLY broker_config_revision
    ADD CONSTRAINT uq_broker_config_revision UNIQUE (cluster_id, revision);

CREATE INDEX ix_broker_config_apply_cluster ON broker_config_apply USING btree (cluster_id, started_at DESC);

ALTER TABLE ONLY broker_config_apply
    ADD CONSTRAINT fk_broker_config_apply_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_config_apply
    ADD CONSTRAINT fk_broker_config_apply_revision FOREIGN KEY (revision_id) REFERENCES broker_config_revision(id);

ALTER TABLE ONLY broker_config_declaration
    ADD CONSTRAINT fk_broker_config_declaration_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_config_declaration
    ADD CONSTRAINT fk_broker_config_declaration_revision FOREIGN KEY (current_revision_id) REFERENCES broker_config_revision(id);

ALTER TABLE ONLY broker_config_node_state
    ADD CONSTRAINT fk_broker_config_node_state_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_config_node_state
    ADD CONSTRAINT fk_broker_config_node_state_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_config_owned_item
    ADD CONSTRAINT fk_broker_config_owned_item_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_config_revision
    ADD CONSTRAINT fk_broker_config_revision_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS broker_config_owned_item CASCADE;
--rollback DROP TABLE IF EXISTS broker_config_node_state CASCADE;
--rollback DROP TABLE IF EXISTS broker_config_apply CASCADE;
--rollback DROP TABLE IF EXISTS broker_config_declaration CASCADE;
--rollback DROP TABLE IF EXISTS broker_config_revision CASCADE;
