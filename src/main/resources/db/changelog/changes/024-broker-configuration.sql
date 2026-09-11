--liquibase formatted sql

-- Broker configuration: declared in Studio, applied over the management API,
-- verified per node (ADR-0067). Five tables, none of them a broker-derived cache:
-- the declaration and its revisions are the operator's record of intent and must
-- survive any cache clear; the per-node state is the latest evaluation and is the
-- only churny one.
--
-- Column order follows the project convention: 8-byte-aligned types first, then
-- 4-byte, then uuid and boolean last.

--changeset artemis-studio:024-broker-config-revision
--comment: append-only. Every save is a new revision; the document is the whole
--         declaration as JSON, keyed by the catalogue's JSON names.
CREATE TABLE broker_config_revision (
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    revision     INTEGER NOT NULL,
    document     JSONB NOT NULL,
    source       TEXT NOT NULL,
    note         TEXT,
    created_by   TEXT NOT NULL,
    cluster_id   UUID NOT NULL,
    CONSTRAINT pk_broker_config_revision PRIMARY KEY (id),
    CONSTRAINT uq_broker_config_revision UNIQUE (cluster_id, revision),
    CONSTRAINT fk_broker_config_revision_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT ck_broker_config_revision_source CHECK (source IN ('EDIT', 'IMPORT_XML', 'ADOPT', 'MCP'))
);
--rollback DROP TABLE broker_config_revision;

--changeset artemis-studio:024-broker-config-declaration
--comment: one row per cluster: which revision is current, how the cluster's
--         configuration is applied, and whether undeclared resources are reported.
CREATE TABLE broker_config_declaration (
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    current_revision_id    BIGINT NOT NULL,
    apply_mode             TEXT NOT NULL DEFAULT 'STUDIO_MANAGED',
    undeclared_exclusions  JSONB NOT NULL DEFAULT '[]'::jsonb,
    cluster_id             UUID NOT NULL,
    report_undeclared      BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT pk_broker_config_declaration PRIMARY KEY (cluster_id),
    CONSTRAINT fk_broker_config_declaration_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT fk_broker_config_declaration_revision FOREIGN KEY (current_revision_id)
        REFERENCES broker_config_revision (id),
    CONSTRAINT ck_broker_config_declaration_mode CHECK (apply_mode IN ('STUDIO_MANAGED', 'CONFIG_MANAGED'))
);
--rollback DROP TABLE broker_config_declaration;

--changeset artemis-studio:024-broker-config-apply
--comment: one row per apply command, dry runs included and flagged, so the plan an
--         operator confirmed and the outcome it produced are readable side by side.
CREATE TABLE broker_config_apply (
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    revision_id     BIGINT NOT NULL,
    audit_event_id  BIGINT,
    plan            JSONB NOT NULL,
    outcome_detail  JSONB,
    outcome         TEXT NOT NULL,
    summary         TEXT,
    actor           TEXT NOT NULL,
    cluster_id      UUID NOT NULL,
    canary_node_id  UUID,
    dry_run         BOOLEAN NOT NULL,
    CONSTRAINT pk_broker_config_apply PRIMARY KEY (id),
    CONSTRAINT fk_broker_config_apply_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT fk_broker_config_apply_revision FOREIGN KEY (revision_id)
        REFERENCES broker_config_revision (id),
    CONSTRAINT ck_broker_config_apply_outcome CHECK (outcome IN ('DRY_RUN', 'APPLIED', 'HALTED', 'FAILED'))
);
CREATE INDEX ix_broker_config_apply_cluster ON broker_config_apply (cluster_id, started_at DESC);
--rollback DROP TABLE broker_config_apply;

--changeset artemis-studio:024-broker-config-node-state
--comment: the latest drift evaluation per node. Rewritten every interval, so it
--         carries the same storage parameters as the other high-churn tables.
CREATE TABLE broker_config_node_state (
    evaluated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_revision  INTEGER,
    findings           JSONB NOT NULL DEFAULT '[]'::jsonb,
    state              TEXT NOT NULL,
    detail             TEXT,
    cluster_id         UUID NOT NULL,
    node_id            UUID NOT NULL,
    CONSTRAINT pk_broker_config_node_state PRIMARY KEY (cluster_id, node_id),
    CONSTRAINT fk_broker_config_node_state_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT fk_broker_config_node_state_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE CASCADE,
    CONSTRAINT ck_broker_config_node_state_state
        CHECK (state IN ('IN_SYNC', 'DRIFTED', 'NOT_EVALUATED', 'UNREACHABLE'))
);

ALTER TABLE broker_config_node_state SET (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.05
);
--rollback DROP TABLE broker_config_node_state;

--changeset artemis-studio:024-broker-config-owned-item
--comment: what Studio itself applied (ADR-0067 D6). An apply removes only an item
--         listed here when it leaves the declaration; anything else is reported.
CREATE TABLE broker_config_owned_item (
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revision_id  BIGINT NOT NULL,
    kind         TEXT NOT NULL,
    item_key     TEXT NOT NULL,
    cluster_id   UUID NOT NULL,
    CONSTRAINT pk_broker_config_owned_item PRIMARY KEY (cluster_id, kind, item_key),
    CONSTRAINT fk_broker_config_owned_item_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT ck_broker_config_owned_item_kind
        CHECK (kind IN ('ADDRESS_SETTING', 'SECURITY_SETTING', 'DIVERT'))
);
--rollback DROP TABLE broker_config_owned_item;
