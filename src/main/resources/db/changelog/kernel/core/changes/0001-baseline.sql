--liquibase formatted sql

-- The kernel/core module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-core-0001-baseline splitStatements:true
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
--rollback DROP EXTENSION IF EXISTS pgcrypto;
