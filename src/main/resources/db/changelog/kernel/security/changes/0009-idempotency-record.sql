--liquibase formatted sql

-- Idempotency keys (ADR-0148): one row per (user, key), claimed PENDING before a mutating request runs
-- and completed with its status and body, so a repeat within 24 hours replays the first result. The
-- data lifecycle purges it (kernel/lifecycle IdempotencyStore). Never edit this file once released.

--changeset artemis-studio:kernel-security-0009-idempotency-record
CREATE TABLE idempotency_record (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    status integer,
    idem_key text NOT NULL,
    fingerprint text NOT NULL,
    state text NOT NULL,
    content_type text,
    body bytea,
    user_id uuid NOT NULL,
    CONSTRAINT ck_idempotency_record_state CHECK (state IN ('PENDING', 'DONE'))
) WITH (autovacuum_vacuum_scale_factor = 0.05);

ALTER TABLE ONLY idempotency_record
    ADD CONSTRAINT pk_idempotency_record PRIMARY KEY (user_id, idem_key);

CREATE INDEX ix_idempotency_record_created_at ON idempotency_record USING btree (created_at);
--rollback DROP TABLE IF EXISTS idempotency_record;
