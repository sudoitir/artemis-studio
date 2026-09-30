--liquibase formatted sql

-- Browsers a user chose to trust after a second factor (ADR-0142). Only the SHA-256 of the cookie's
-- random token is kept. expires_at is when this row stops counting; the trusted-device lifetime setting
-- can shorten that, never lengthen it. Column order follows non-negotiable #7.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-identitylocal-0003-trusted-devices
CREATE TABLE local_trusted_device (
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone DEFAULT now() NOT NULL,
    user_agent text,
    client_address text,
    token_hash bytea NOT NULL,
    user_id uuid NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT pk_local_trusted_device PRIMARY KEY (id),
    CONSTRAINT fk_local_trusted_device_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX ix_local_trusted_device_user ON local_trusted_device (user_id);
--rollback DROP TABLE local_trusted_device;
