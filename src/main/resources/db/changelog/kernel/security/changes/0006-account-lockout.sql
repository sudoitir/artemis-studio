--liquibase formatted sql

-- Account lockout (ADR-0144): consecutive failed sign-ins and, once they reach the limit, the
-- moment the lock lifts. Written only by AccountLockout, with atomic statements, so the
-- entity maps both columns read-only. A lock that has passed is treated as absent; no job clears it.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0006-account-lockout
ALTER TABLE app_user
    ADD COLUMN locked_until timestamp with time zone,
    ADD COLUMN failed_login_count integer DEFAULT 0 NOT NULL;
--rollback ALTER TABLE app_user DROP COLUMN locked_until, DROP COLUMN failed_login_count;
