--liquibase formatted sql

--changeset artemis-studio:001-extensions
--comment: pgcrypto for gen_random_uuid(); safe to run everywhere.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
--rollback DROP EXTENSION IF EXISTS pgcrypto;
