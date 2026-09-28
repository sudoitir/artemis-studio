--liquibase formatted sql

-- ShedLock's lock table (ADR-0125): one row per installation-wide job, holding who runs it and
-- until when, in UTC written by the database (usingDbTime), hence timestamp without time zone.
-- The layout is the library's own, so column order does not follow the padding rule.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-jobs-0001-shedlock
CREATE TABLE shedlock (
    name varchar(64) NOT NULL,
    lock_until timestamp NOT NULL,
    locked_at timestamp NOT NULL,
    locked_by varchar(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
