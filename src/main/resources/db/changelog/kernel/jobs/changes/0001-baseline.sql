--liquibase formatted sql

-- The kernel/jobs module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-jobs-0001-baseline splitStatements:true
CREATE TABLE shedlock (
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    name character varying(64) NOT NULL,
    locked_by character varying(255) NOT NULL
);

ALTER TABLE ONLY shedlock
    ADD CONSTRAINT pk_shedlock PRIMARY KEY (name);
--rollback DROP TABLE IF EXISTS shedlock CASCADE;
