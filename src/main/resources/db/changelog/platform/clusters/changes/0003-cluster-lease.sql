--liquibase formatted sql

-- Which replica owns a cluster's broker work (ADR-0148): one row per owned cluster, renewed every
-- heartbeat and expiring a ttl later. A released lease is not deleted but expired at once, so a
-- cluster nobody has taken up for one more ttl stays distinguishable from one just released. Column
-- order follows the padding rule (8-byte first, then uuid last). Rows are rewritten every few
-- seconds, hence the storage parameters. Never edit this file once released; add a new changeset
-- beside it.

--changeset artemis-studio:platform-clusters-0003-cluster-lease
CREATE TABLE cluster_lease (
    expires_at timestamp with time zone NOT NULL,
    replica_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT pk_cluster_lease PRIMARY KEY (cluster_id),
    CONSTRAINT fk_cluster_lease_cluster FOREIGN KEY (cluster_id) REFERENCES cluster (id) ON DELETE CASCADE
) WITH (fillfactor = 50, autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 50);
--rollback DROP TABLE IF EXISTS cluster_lease;
