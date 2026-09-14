--liquibase formatted sql

-- The kernel/settings module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

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
--rollback DROP TABLE IF EXISTS studio_config_property CASCADE;
--rollback DROP TABLE IF EXISTS studio_setting CASCADE;
