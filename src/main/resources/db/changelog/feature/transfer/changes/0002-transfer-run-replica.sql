--liquibase formatted sql

-- The replica executing a transfer run (ADR-0148): set whenever a segment begins (execute, resume
-- or return), so recovery interrupts a run only when its replica is gone, never one another replica
-- is executing. No foreign key: the replicas store reaps old rows, and a run outlives its replica.
-- A run with no replica_id predates this column and counts as orphaned. Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:feature-transfer-0002-transfer-run-replica
ALTER TABLE transfer_run ADD COLUMN replica_id uuid;
--rollback ALTER TABLE transfer_run DROP COLUMN replica_id;
