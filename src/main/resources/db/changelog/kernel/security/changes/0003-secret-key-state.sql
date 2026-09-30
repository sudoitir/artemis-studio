--liquibase formatted sql

-- The KEK version every replica wraps new secrets with (ADR-0132 D4), stored so it does not depend on the
-- order replicas restart in. A single row: the primary key is the constant true. Filled on first start with the
-- highest version the key provider holds.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0003-secret-key-state
CREATE TABLE secret_key_state (
    updated_at timestamp with time zone NOT NULL,
    current_kek_version integer NOT NULL,
    singleton boolean DEFAULT true NOT NULL
);

ALTER TABLE ONLY secret_key_state
    ADD CONSTRAINT pk_secret_key_state PRIMARY KEY (singleton);

ALTER TABLE ONLY secret_key_state
    ADD CONSTRAINT ck_secret_key_state_single CHECK (singleton);
--rollback DROP TABLE IF EXISTS secret_key_state CASCADE;
