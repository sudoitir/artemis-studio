--liquibase formatted sql

-- The split-brain verdict of a broker node (ADR-0152): written by the replica that owns the cluster in
-- the same tier-A pass that records the node's HA state, read by every replica, so a transfer or a
-- configuration apply that lands on another replica is refused for the same reason. It was in memory
-- on the replica that scraped. The verdict is per NodeID (ADR-0012), so both members of a pair carry
-- the same value. NONE, SUSPECTED (seen once) or CRITICAL (seen again on a later cycle). Never edit this
-- file once released; add a new changeset beside it.

--changeset artemis-studio:platform-clusters-0004-broker-node-split-brain
ALTER TABLE broker_node ADD COLUMN split_brain text NOT NULL DEFAULT 'NONE';
ALTER TABLE broker_node ADD CONSTRAINT ck_broker_node_split_brain CHECK (split_brain IN ('NONE', 'SUSPECTED', 'CRITICAL'));
--rollback ALTER TABLE broker_node DROP COLUMN split_brain;
