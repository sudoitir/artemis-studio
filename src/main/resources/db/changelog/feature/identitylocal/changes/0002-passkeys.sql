--liquibase formatted sql

-- Passkeys of local accounts (ADR-0143), in the two tables Spring Security's
-- JdbcPublicKeyCredentialUserEntityRepository and JdbcUserCredentialRepository read and write. Their
-- table and column names are Spring's, because its SQL names them; the types are PostgreSQL's
-- (text for its varchar ids, bytea for its blobs, timestamptz for its timestamps) and the column
-- order follows non-negotiable #7 where that does not matter to Spring.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-identitylocal-0002-passkeys
-- One row per user with a passkey. name is the user's id as text (not the username, which an
-- account could one day change); display_name is the username. id is the WebAuthn user handle.
-- Spring's repository binds name as text, so it cannot be a uuid; user_id is that same id as a uuid,
-- computed by the database, so the row (and through it the user's passkeys) goes when the user does.
CREATE TABLE user_entities (
    id text NOT NULL,
    name text NOT NULL,
    display_name text,
    user_id uuid GENERATED ALWAYS AS (name::uuid) STORED NOT NULL,
    CONSTRAINT pk_user_entities PRIMARY KEY (id),
    CONSTRAINT uq_user_entities_name UNIQUE (name),
    CONSTRAINT fk_user_entities_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

-- One row per passkey. credential_id and user_entity_user_id are base64url.
CREATE TABLE user_credentials (
    created timestamp with time zone,
    last_used timestamp with time zone,
    signature_count bigint,
    credential_id text NOT NULL,
    user_entity_user_id text NOT NULL,
    authenticator_transports text,
    public_key_credential_type text,
    label text NOT NULL,
    public_key bytea NOT NULL,
    attestation_object bytea,
    attestation_client_data_json bytea,
    uv_initialized boolean,
    backup_eligible boolean NOT NULL,
    backup_state boolean NOT NULL,
    CONSTRAINT pk_user_credentials PRIMARY KEY (credential_id),
    CONSTRAINT fk_user_credentials_user_entity FOREIGN KEY (user_entity_user_id) REFERENCES user_entities(id) ON DELETE CASCADE
);

CREATE INDEX ix_user_credentials_user_entity ON user_credentials (user_entity_user_id);
--rollback DROP TABLE user_credentials; DROP TABLE user_entities;
