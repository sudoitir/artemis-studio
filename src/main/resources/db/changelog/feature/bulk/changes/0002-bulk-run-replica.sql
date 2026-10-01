--liquibase formatted sql

-- The replica executing a bulk run (ADR-0152): set when the run starts, so recovery interrupts a
-- run only when its replica is gone, never one another replica is executing. No foreign key: the
-- replicas store reaps old rows, and a run outlives its replica. A run with no replica_id predates
-- this column and counts as orphaned. Never edit this file once released; add a new changeset
-- beside it.

--changeset artemis-studio:feature-bulk-0002-bulk-run-replica
ALTER TABLE bulk_run ADD COLUMN replica_id uuid;
--rollback ALTER TABLE bulk_run DROP COLUMN replica_id;
