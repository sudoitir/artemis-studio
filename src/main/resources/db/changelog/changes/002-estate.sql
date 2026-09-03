--liquibase formatted sql

-- Estate: environments -> clusters -> broker nodes, plus per-cluster
-- credentials and TLS. Postgres is the source of truth for all of this.
-- Column order in every table: 8-byte-aligned (timestamptz/bigint) first,
-- then 4-byte (integer, text, jsonb, bytea), then uuid and boolean last.

--changeset artemis-studio:002-estate-environment
CREATE TABLE environment (
    sort_order  INTEGER NOT NULL DEFAULT 0,
    name        TEXT    NOT NULL,
    colour      TEXT,
    id          UUID    NOT NULL DEFAULT gen_random_uuid(),
    CONSTRAINT pk_environment PRIMARY KEY (id),
    CONSTRAINT uq_environment_name UNIQUE (name)
);
--rollback DROP TABLE environment;

--changeset artemis-studio:002-estate-cluster
CREATE TABLE cluster (
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    name            TEXT NOT NULL,
    description     TEXT,
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    environment_id  UUID,
    read_only       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_cluster PRIMARY KEY (id),
    CONSTRAINT fk_cluster_environment FOREIGN KEY (environment_id)
        REFERENCES environment (id) ON DELETE SET NULL,
    CONSTRAINT uq_cluster_env_name UNIQUE (environment_id, name)
);
--rollback DROP TABLE cluster;

--changeset artemis-studio:002-estate-broker-node
CREATE TABLE broker_node (
    last_seen_at     TIMESTAMPTZ,
    artemis_node_id  TEXT,                 -- broker-reported node UUID; null until first contact
    name             TEXT NOT NULL,
    jolokia_url      TEXT,
    core_url         TEXT,
    ha_role          TEXT NOT NULL DEFAULT 'STANDALONE',
    pair_group       TEXT,                 -- groups a primary with its backup
    state            TEXT NOT NULL DEFAULT 'UNKNOWN',
    version          TEXT,
    last_error       TEXT,
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id       UUID NOT NULL,
    discovered       BOOLEAN NOT NULL DEFAULT FALSE,   -- learned via listNetworkTopology()
    manual_override  BOOLEAN NOT NULL DEFAULT FALSE,   -- true = discovery must not overwrite URLs
    CONSTRAINT pk_broker_node PRIMARY KEY (id),
    CONSTRAINT fk_broker_node_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT uq_broker_node_cluster_name UNIQUE (cluster_id, name),
    CONSTRAINT ck_broker_node_ha_role CHECK (ha_role IN ('PRIMARY', 'BACKUP', 'STANDALONE'))
);
CREATE INDEX ix_broker_node_cluster ON broker_node (cluster_id);
--rollback DROP TABLE broker_node;

--changeset artemis-studio:002-estate-broker-credential
CREATE TABLE broker_credential (
    kind          TEXT  NOT NULL,          -- JOLOKIA_BASIC | CORE
    username      TEXT,
    secret_ct     BYTEA NOT NULL,          -- AES-GCM ciphertext; key from env/file, never stored
    secret_nonce  BYTEA NOT NULL,
    id            UUID  NOT NULL DEFAULT gen_random_uuid(),
    cluster_id    UUID  NOT NULL,
    CONSTRAINT pk_broker_credential PRIMARY KEY (id),
    CONSTRAINT fk_broker_credential_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT uq_broker_credential_cluster_kind UNIQUE (cluster_id, kind)
);
--rollback DROP TABLE broker_credential;

--changeset artemis-studio:002-estate-broker-tls
CREATE TABLE broker_tls (
    truststore_ref   TEXT,
    client_cert_ref  TEXT,
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    cluster_id       UUID NOT NULL,
    verify_hostname  BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_broker_tls PRIMARY KEY (id),
    CONSTRAINT fk_broker_tls_cluster FOREIGN KEY (cluster_id)
        REFERENCES cluster (id) ON DELETE CASCADE,
    CONSTRAINT uq_broker_tls_cluster UNIQUE (cluster_id)
);
--rollback DROP TABLE broker_tls;
