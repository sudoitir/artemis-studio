--liquibase formatted sql

-- The kernel/core module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-core-0001-baseline splitStatements:true
CREATE EXTENSION IF NOT EXISTS pg_trgm;
--rollback DROP EXTENSION IF EXISTS pg_trgm;
