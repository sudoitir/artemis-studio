--liquibase formatted sql

-- Which cluster each broker belongs to (ADR-0167). A registration claims the NodeID of every node it
-- discovers, and the management URL (normal form) of a seed whose broker reported no NodeID. The primary
-- key is what makes a broker belong to one cluster only: of two registrations of the same brokers racing,
-- the second finds the first's claim and is refused. A cluster's claims go with it. The table starts empty:
-- clusters registered before it are still found through their broker_node rows, which the registration
-- check reads too, so nothing is backfilled. Column order follows the padding rule (text, then uuid).
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:platform-clusters-0006-broker-identity
CREATE TABLE broker_identity (
    kind text NOT NULL,
    identity text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT pk_broker_identity PRIMARY KEY (kind, identity),
    CONSTRAINT ck_broker_identity_kind CHECK (kind IN ('NODE_ID', 'URL')),
    CONSTRAINT fk_broker_identity_cluster FOREIGN KEY (cluster_id) REFERENCES cluster (id) ON DELETE CASCADE
);
CREATE INDEX ix_broker_identity_cluster ON broker_identity (cluster_id);
--rollback DROP TABLE IF EXISTS broker_identity;
