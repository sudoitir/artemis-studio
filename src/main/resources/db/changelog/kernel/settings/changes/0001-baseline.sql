--liquibase formatted sql

-- The kernel/settings module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-settings-0001-baseline splitStatements:true
CREATE TABLE rahdar_config_property (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    application text NOT NULL,
    profile text NOT NULL,
    label text NOT NULL,
    key text NOT NULL,
    value text
);

CREATE TABLE rahdar_setting (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    value jsonb NOT NULL,
    key text NOT NULL
);

ALTER TABLE ONLY rahdar_config_property
    ADD CONSTRAINT pk_rahdar_config_property PRIMARY KEY (application, profile, label, key);

ALTER TABLE ONLY rahdar_setting
    ADD CONSTRAINT pk_rahdar_setting PRIMARY KEY (key);
--rollback DROP TABLE IF EXISTS rahdar_setting CASCADE;
--rollback DROP TABLE IF EXISTS rahdar_config_property CASCADE;
