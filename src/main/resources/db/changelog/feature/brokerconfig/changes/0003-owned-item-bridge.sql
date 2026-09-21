--liquibase formatted sql

-- Bridges become declared items (ADR-0091), so the ownership record that makes "remove only what Studio applied"
-- true has to be able to name one. The check constraint is widened; no table, no data migration, no backfill —
-- a declaration recorded before this change simply has no bridges.
-- Never edit this file once released.

--changeset artemis-studio:feature-brokerconfig-0003-owned-item-bridge splitStatements:true
ALTER TABLE broker_config_owned_item DROP CONSTRAINT ck_broker_config_owned_item_kind;
ALTER TABLE broker_config_owned_item
    ADD CONSTRAINT ck_broker_config_owned_item_kind
        CHECK ((kind = ANY (ARRAY['ADDRESS_SETTING'::text, 'SECURITY_SETTING'::text, 'DIVERT'::text, 'BRIDGE'::text])));
--rollback DELETE FROM broker_config_owned_item WHERE kind = 'BRIDGE';
--rollback ALTER TABLE broker_config_owned_item DROP CONSTRAINT ck_broker_config_owned_item_kind;
--rollback ALTER TABLE broker_config_owned_item ADD CONSTRAINT ck_broker_config_owned_item_kind CHECK ((kind = ANY (ARRAY['ADDRESS_SETTING'::text, 'SECURITY_SETTING'::text, 'DIVERT'::text])));
