--liquibase formatted sql

-- Operator-tunable configuration, overriding the application.yml defaults at
-- runtime (ADR-0015 area). Low churn: no special autovacuum params. One row per
-- setting key; the value is a JSON scalar or object so a key can grow structure
-- later without a migration.

--changeset artemis-studio:009-studio-settings
CREATE TABLE studio_setting (
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    value      JSONB       NOT NULL,
    key        TEXT        NOT NULL,
    CONSTRAINT pk_studio_setting PRIMARY KEY (key)
);
--rollback DROP TABLE studio_setting;
