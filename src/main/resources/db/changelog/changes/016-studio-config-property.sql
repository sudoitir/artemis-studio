--liquibase formatted sql

-- Deploy-time configuration properties, loaded into the Spring Environment during
-- the bootstrap phase (ADR-0047). This is the second, colder configuration plane:
-- studio_setting (009) holds what an operator changes from the Settings screen and
-- applies immediately; this holds what a deployment sets once — the things that are
-- read while the application is starting and so cannot be changed from inside it.
--
-- The shape is Spring Cloud Config's JDBC key-value model (application / profile /
-- label / key), so the same rows could be served to another consumer later without
-- a migration. Low churn: no special autovacuum params. Ships empty on purpose —
-- an absent row means "use the packaged default", exactly as in studio_setting.
--
-- "key" and "value" are quoted throughout: `value` is a reserved word in SQL and
-- `key` is non-reserved but unquoted use is a trap worth not setting.

--changeset artemis-studio:016-studio-config-property
CREATE TABLE studio_config_property (
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    application TEXT NOT NULL,
    profile     TEXT NOT NULL,
    label       TEXT NOT NULL,
    "key"       TEXT NOT NULL,
    "value"     TEXT,
    CONSTRAINT pk_studio_config_property PRIMARY KEY (application, profile, label, "key")
);
--rollback DROP TABLE studio_config_property;
