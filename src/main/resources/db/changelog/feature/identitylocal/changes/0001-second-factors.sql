--liquibase formatted sql

-- Second factors of local accounts (ADR-0143). Column order follows non-negotiable #7.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-identitylocal-0001-second-factors
-- A user's TOTP authenticator. Each secret is one blob sealed by SecretVault (ADR-0132) under the AAD
-- 'local_totp:<user id>', in a column named sealed so a key rotation re-wraps it with the other stores.
-- A new secret waits in local_totp_pending and replaces the active one in local_totp only once a code
-- from it is confirmed. last_step is the newest 30-second step a code was accepted for, so a code
-- cannot be used twice.
CREATE TABLE local_totp (
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL,
    last_step bigint DEFAULT 0 NOT NULL,
    sealed bytea NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT pk_local_totp PRIMARY KEY (user_id),
    CONSTRAINT fk_local_totp_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE local_totp_pending (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sealed bytea NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT pk_local_totp_pending PRIMARY KEY (user_id),
    CONSTRAINT fk_local_totp_pending_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

-- Single-use recovery codes, kept as the HMAC-SHA256 of the code without dashes, upper-cased, under the
-- installation's recovery-code key.
CREATE TABLE local_recovery_code (
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    code_hash bytea NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT pk_local_recovery_code PRIMARY KEY (user_id, code_hash),
    CONSTRAINT fk_local_recovery_code_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

-- The HMAC key of the recovery codes: 32 random bytes made once and sealed by SecretVault under the AAD
-- 'local_recovery_key'. It is a stored secret of its own rather than a key derived from a key-encryption
-- key, so rotating or retiring a key version (ADR-0132) re-wraps it and never changes it, and every hash
-- stays valid. A single row: the primary key is the constant true.
CREATE TABLE local_recovery_key (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sealed bytea NOT NULL,
    singleton boolean DEFAULT true NOT NULL,
    CONSTRAINT pk_local_recovery_key PRIMARY KEY (singleton),
    CONSTRAINT ck_local_recovery_key_single CHECK (singleton)
);
--rollback DROP TABLE local_recovery_key; DROP TABLE local_recovery_code; DROP TABLE local_totp_pending; DROP TABLE local_totp;
