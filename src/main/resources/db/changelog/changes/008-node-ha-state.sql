--liquibase formatted sql

-- Phase 1: per-node HA state learned by polling (never from broker config).
-- Additive only; changesets 001-007 are released and not touched (ADR-0008).
--
-- observed_cycle is the monotonic refresh-cycle number of the last successful
-- read of this node. HaStateEvaluator uses it to require that a dual-Active
-- split-brain sighting is corroborated within one cycle and confirmed on the
-- next (ADR-0012), so a planned failover does not raise a false CRITICAL.
--
-- active / replica_sync mirror the broker's Active and ReplicaSync attributes.
-- Node health comes from state (derived from Started), not from active: a
-- healthy backup is Started=true, Active=false.

--changeset artemis-studio:008-node-ha-state
ALTER TABLE broker_node ADD COLUMN observed_cycle BIGINT;
ALTER TABLE broker_node ADD COLUMN active         BOOLEAN;
ALTER TABLE broker_node ADD COLUMN replica_sync   BOOLEAN;
--rollback ALTER TABLE broker_node DROP COLUMN replica_sync;
--rollback ALTER TABLE broker_node DROP COLUMN active;
--rollback ALTER TABLE broker_node DROP COLUMN observed_cycle;
