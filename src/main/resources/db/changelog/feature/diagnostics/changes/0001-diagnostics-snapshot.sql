--liquibase formatted sql

-- Support bundles prepared for preview and not yet downloaded (ADR-0148): the download may reach
-- another replica than the preparation did, so the snapshot lives here and not in a replica's
-- memory. A snapshot is worthless ten minutes after it is made and is lost, harmlessly, on a
-- crash, so the table is UNLOGGED. Expired rows are purged by the data lifecycle (ADR-0134).
-- Column order follows the padding rule (8-byte first, then text, uuid last). Never edit this
-- file once released; add a new changeset beside it.

--changeset artemis-studio:feature-diagnostics-0001-diagnostics-snapshot
CREATE UNLOGGED TABLE diagnostics_snapshot (
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    owner text NOT NULL,
    sections text NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT pk_diagnostics_snapshot PRIMARY KEY (id)
) WITH (autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 50);
CREATE INDEX ix_diagnostics_snapshot_expires_at ON diagnostics_snapshot (expires_at);
--rollback DROP TABLE IF EXISTS diagnostics_snapshot;
