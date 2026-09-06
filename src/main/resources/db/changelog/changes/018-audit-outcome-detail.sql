--liquibase formatted sql

-- Queue and address lifecycle (ADR-0049). A lifecycle command names a cluster and
-- fans out to every live node, so one operator action has N broker calls with
-- genuinely different results — applied here, already-in-state there, skipped on a
-- node that was not live, failed on a fourth.
--
-- D4 records that as ONE audit row, not one per node: one row per node would make
-- the fan-out invisible, and a partial failure would read as unrelated events. The
-- per-node detail therefore has to live on the row, and neither `params` (the
-- request, and immutable) nor `error` (text, and a lie on a partial success) is the
-- right home for it.
--
-- `004-audit.sql` is released and is never edited.

--changeset artemis-studio:018-audit-event-outcome-detail
--comment: JSONB joins the existing 4-byte alignment group next to params, ahead of
--         the uuid/boolean tail, per the column-ordering convention. Nullable: only
--         a fan-out command has per-node detail, and every existing row has none.
--         fillfactor stays 100 — this is set by the same UPDATE that already sets
--         `outcome`, so it adds no new in-place update to leave space for.
ALTER TABLE audit_event ADD COLUMN outcome_detail JSONB;

COMMENT ON COLUMN audit_event.outcome_detail IS
    'Per-node outcome of a cluster-wide fan-out command (ADR-0049 D2/D4): a JSON array of '
    '{nodeId, nodeName, status, affected, error}. Null for single-node actions.';
--rollback ALTER TABLE audit_event DROP COLUMN outcome_detail;
