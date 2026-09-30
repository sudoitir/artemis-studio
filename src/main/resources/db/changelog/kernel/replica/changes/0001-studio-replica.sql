--liquibase formatted sql

-- The replicas of this installation (ADR-0152): every Studio process registers itself here and
-- heartbeats with database time. A row with no stopped_at and a stale heartbeat is a crash, which
-- is what the plugin crash-loop guard counts. Rows are kept bounded by the replicas store
-- (ADR-0134). Column order follows the padding rule (8-byte first, then 4-byte, uuid last). Never
-- edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-replica-0001-studio-replica
CREATE TABLE studio_replica (
    started_at timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL,
    stopped_at timestamp with time zone,
    host text NOT NULL,
    version text NOT NULL,
    state text NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT pk_studio_replica PRIMARY KEY (id),
    CONSTRAINT ck_studio_replica_state CHECK (state IN ('starting', 'ready', 'draining', 'stopped'))
) WITH (fillfactor = 50, autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 50);
--rollback DROP TABLE IF EXISTS studio_replica;
