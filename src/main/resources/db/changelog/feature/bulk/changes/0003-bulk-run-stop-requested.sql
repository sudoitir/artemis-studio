--liquibase formatted sql

-- When an operator asked to stop the run (ADR-0152). The stop endpoint sets it and signals the other
-- replicas as a fast path; the runner reads it between queues, so a stop whose signal was lost, for
-- example while the executing replica's bus connection was down, still stops the run. Appended last
-- because ALTER TABLE cannot reorder columns; a nullable timestamptz costs no row when unset. Never
-- edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-bulk-0003-bulk-run-stop-requested
ALTER TABLE bulk_run ADD COLUMN stop_requested_at timestamptz;
--rollback ALTER TABLE bulk_run DROP COLUMN stop_requested_at;
