--liquibase formatted sql

-- The kernel/settings module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-settings-0001-baseline splitStatements:true
CREATE TABLE studio_config_property (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    application text NOT NULL,
    profile text NOT NULL,
    label text NOT NULL,
    key text NOT NULL,
    value text
);

CREATE TABLE studio_setting (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    value jsonb NOT NULL,
    key text NOT NULL
);

ALTER TABLE ONLY studio_config_property
    ADD CONSTRAINT pk_studio_config_property PRIMARY KEY (application, profile, label, key);

ALTER TABLE ONLY studio_setting
    ADD CONSTRAINT pk_studio_setting PRIMARY KEY (key);
--rollback DROP TABLE IF EXISTS studio_setting CASCADE;
--rollback DROP TABLE IF EXISTS studio_config_property CASCADE;
