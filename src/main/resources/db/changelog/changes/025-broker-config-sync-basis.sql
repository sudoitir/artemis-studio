--liquibase formatted sql

-- Why a node is IN_SYNC, not merely that it is.
--
-- A node reached agreement with the declaration in one of three ways, and they are
-- not equally strong evidence: Studio wrote the values and read them back
-- (VERIFIED_APPLY); the operator declared what the broker was already running, so
-- the declaration moved and the broker did not (ADOPTED); or an evaluation simply
-- found them equal, which is the honest answer for a CONFIG_MANAGED cluster whose
-- broker.xml someone else deploys (OBSERVED_MATCH). Recording only the state let
-- adoption close a drift finding and leave a screen that read exactly like a
-- successful apply.
--
-- 024-broker-configuration.sql is unreleased but already applied on dev instances,
-- so it is not edited: changing a changeset that has run turns into a checksum
-- failure on the next boot.

--changeset artemis-studio:025-broker-config-node-state-basis
--comment: the evidence behind the state. basis_ref points at the apply (VERIFIED_APPLY)
--         or the revision number (ADOPTED); OBSERVED_MATCH has no reference to give.
ALTER TABLE broker_config_node_state
    ADD COLUMN basis_ref BIGINT,
    ADD COLUMN basis     TEXT;

ALTER TABLE broker_config_node_state
    ADD CONSTRAINT ck_broker_config_node_state_basis
        CHECK (basis IS NULL OR basis IN ('VERIFIED_APPLY', 'ADOPTED', 'OBSERVED_MATCH'));
--rollback ALTER TABLE broker_config_node_state DROP CONSTRAINT ck_broker_config_node_state_basis;
--rollback ALTER TABLE broker_config_node_state DROP COLUMN basis, DROP COLUMN basis_ref;

--changeset artemis-studio:025-broker-config-revision-source-recommended
--comment: a revision Studio proposed from the capability probe's recommendations and
--         the operator confirmed. Distinct from EDIT because its provenance is what
--         lets the ledger say which gap a revision came from.
ALTER TABLE broker_config_revision
    DROP CONSTRAINT ck_broker_config_revision_source;

ALTER TABLE broker_config_revision
    ADD CONSTRAINT ck_broker_config_revision_source
        CHECK (source IN ('EDIT', 'IMPORT_XML', 'ADOPT', 'MCP', 'RECOMMENDED'));
--rollback ALTER TABLE broker_config_revision DROP CONSTRAINT ck_broker_config_revision_source;
--rollback ALTER TABLE broker_config_revision ADD CONSTRAINT ck_broker_config_revision_source CHECK (source IN ('EDIT', 'IMPORT_XML', 'ADOPT', 'MCP'));
