--liquibase formatted sql

-- One row per key-encryption-key rotation (ADR-0132 D5). A rotation is RUNNING until every sealed store holds
-- only blobs wrapped under its target version. The partial unique index allows at most one RUNNING row.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0005-secret-rotation
CREATE TABLE secret_rotation (
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    rewrapped bigint DEFAULT 0 NOT NULL,
    remaining bigint DEFAULT 0 NOT NULL,
    from_version integer NOT NULL,
    to_version integer NOT NULL,
    status text NOT NULL,
    started_by text NOT NULL,
    error text,
    id uuid NOT NULL,
    CONSTRAINT ck_secret_rotation_status CHECK ((status = ANY (ARRAY['RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY secret_rotation
    ADD CONSTRAINT pk_secret_rotation PRIMARY KEY (id);

CREATE UNIQUE INDEX ux_secret_rotation_running ON secret_rotation ((true)) WHERE (status = 'RUNNING'::text);
--rollback DROP TABLE IF EXISTS secret_rotation CASCADE;
