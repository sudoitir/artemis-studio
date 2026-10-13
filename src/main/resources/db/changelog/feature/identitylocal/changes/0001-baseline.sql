--liquibase formatted sql

-- The feature/identitylocal module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:feature-identitylocal-0001-baseline splitStatements:true
CREATE TABLE local_recovery_code (
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    code_hash bytea NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE local_recovery_key (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sealed bytea NOT NULL,
    singleton boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_local_recovery_key_single CHECK (singleton)
);

CREATE TABLE local_totp (
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL,
    last_step bigint DEFAULT 0 NOT NULL,
    sealed bytea NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE local_totp_pending (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sealed bytea NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE local_trusted_device (
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone DEFAULT now() NOT NULL,
    user_agent text,
    client_address text,
    token_hash bytea NOT NULL,
    user_id uuid NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL
);

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
    backup_state boolean NOT NULL
);

CREATE TABLE user_entities (
    id text NOT NULL,
    name text NOT NULL,
    display_name text,
    user_id uuid GENERATED ALWAYS AS ((name)::uuid) STORED NOT NULL
);

ALTER TABLE ONLY local_recovery_code
    ADD CONSTRAINT pk_local_recovery_code PRIMARY KEY (user_id, code_hash);

ALTER TABLE ONLY local_recovery_key
    ADD CONSTRAINT pk_local_recovery_key PRIMARY KEY (singleton);

ALTER TABLE ONLY local_totp
    ADD CONSTRAINT pk_local_totp PRIMARY KEY (user_id);

ALTER TABLE ONLY local_totp_pending
    ADD CONSTRAINT pk_local_totp_pending PRIMARY KEY (user_id);

ALTER TABLE ONLY local_trusted_device
    ADD CONSTRAINT pk_local_trusted_device PRIMARY KEY (id);

ALTER TABLE ONLY user_credentials
    ADD CONSTRAINT pk_user_credentials PRIMARY KEY (credential_id);

ALTER TABLE ONLY user_entities
    ADD CONSTRAINT pk_user_entities PRIMARY KEY (id);

ALTER TABLE ONLY user_entities
    ADD CONSTRAINT uq_user_entities_name UNIQUE (name);

CREATE INDEX ix_local_trusted_device_user ON local_trusted_device USING btree (user_id);

CREATE INDEX ix_user_credentials_user_entity ON user_credentials USING btree (user_entity_user_id);

CREATE INDEX ix_user_entities_user_id ON user_entities USING btree (user_id);

ALTER TABLE ONLY local_recovery_code
    ADD CONSTRAINT fk_local_recovery_code_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY local_totp_pending
    ADD CONSTRAINT fk_local_totp_pending_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY local_totp
    ADD CONSTRAINT fk_local_totp_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY local_trusted_device
    ADD CONSTRAINT fk_local_trusted_device_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_credentials
    ADD CONSTRAINT fk_user_credentials_user_entity FOREIGN KEY (user_entity_user_id) REFERENCES user_entities(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_entities
    ADD CONSTRAINT fk_user_entities_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS user_entities CASCADE;
--rollback DROP TABLE IF EXISTS user_credentials CASCADE;
--rollback DROP TABLE IF EXISTS local_trusted_device CASCADE;
--rollback DROP TABLE IF EXISTS local_totp_pending CASCADE;
--rollback DROP TABLE IF EXISTS local_totp CASCADE;
--rollback DROP TABLE IF EXISTS local_recovery_key CASCADE;
--rollback DROP TABLE IF EXISTS local_recovery_code CASCADE;
