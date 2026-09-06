--liquibase formatted sql

-- Management-write capability becomes evidence-backed (ADR-0049 D5).
--
-- Until now `managementWrite` was INFERRED from a read-only listNetworkTopology()
-- call succeeding, and nothing depended on the inference. The moment a create
-- button depends on it, the capability model is lying to the operator — a direct
-- violation of non-negotiable #5 — so the assessment now comes from an actual
-- write having been attempted.
--
-- That evidence has to outlive the request that produced it: the probe itself
-- makes no write (and must not), so without somewhere to keep the observation
-- every probe would answer UNKNOWN forever. It belongs on the cluster, because
-- that is what the connection is a property of.
--
-- `002-estate.sql` is released and is never edited.

--changeset artemis-studio:019-cluster-management-write-evidence
--comment: TIMESTAMPTZ leads the 8-byte group; the two TEXT columns join the 4-byte
--         group ahead of the uuid/boolean tail, per the column-ordering convention.
--         All nullable: a cluster that has never been written to has no evidence,
--         which is exactly the UNKNOWN the capability now reports.
ALTER TABLE cluster
    ADD COLUMN management_write_observed_at TIMESTAMPTZ,
    ADD COLUMN management_write_status      TEXT,
    ADD COLUMN management_write_reason      TEXT;

ALTER TABLE cluster
    ADD CONSTRAINT ck_cluster_management_write_status
        CHECK (management_write_status IS NULL OR management_write_status IN ('AVAILABLE', 'UNAVAILABLE'));

COMMENT ON COLUMN cluster.management_write_status IS
    'Evidence of an attempted management write (ADR-0049 D5): AVAILABLE once one has succeeded, '
    'UNAVAILABLE once one has been refused for an authorization reason, NULL until one is attempted. '
    'A write that fails for any other reason must not set this.';

--rollback ALTER TABLE cluster DROP CONSTRAINT ck_cluster_management_write_status;
--rollback ALTER TABLE cluster DROP COLUMN management_write_reason, DROP COLUMN management_write_status, DROP COLUMN management_write_observed_at;
