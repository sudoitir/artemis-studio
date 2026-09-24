--liquibase formatted sql

-- Runtime plugins (ADR-0099): one row per installed plugin id, updated in place as its status
-- changes (so it stays tiny and its updates HOT). previous_sha256 is kept so an Instant-class
-- update that wrote no changeset can roll back without a re-upload. Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0002-plugin-install
CREATE TABLE plugin_install (
    installed_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    id text NOT NULL,
    version text NOT NULL,
    vendor text NOT NULL,
    sha256 text NOT NULL,
    previous_sha256 text,
    status text NOT NULL,
    failure text,
    installed_by text,
    descriptor jsonb NOT NULL,
    schema_changed boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_plugin_install_status CHECK (status IN (
        'activating', 'active', 'disabled', 'failed', 'incompatible', 'needs_restart', 'uninstalled'))
);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT pk_plugin_install PRIMARY KEY (id);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT fk_plugin_install_sha256 FOREIGN KEY (sha256) REFERENCES plugin_artifact(sha256);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT fk_plugin_install_previous_sha256 FOREIGN KEY (previous_sha256) REFERENCES plugin_artifact(sha256);
--rollback DROP TABLE IF EXISTS plugin_install CASCADE;
