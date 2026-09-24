--liquibase formatted sql

-- Runtime plugins (ADR-0099..0103): an inspected, inert upload awaiting activation. Uploading a
-- jar validates and stores it (kernel.plugin.internal.store.PluginStore, task 6.5) without
-- touching plugin_install; a row here is what the admin API's review screen reads before the
-- operator arms activation. Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0005-plugin-upload
CREATE TABLE plugin_upload (
    uploaded_at timestamp with time zone NOT NULL,
    sha256 text NOT NULL,
    plugin_id text NOT NULL,
    uploaded_by text NOT NULL,
    descriptor jsonb NOT NULL,
    report jsonb NOT NULL
);

ALTER TABLE ONLY plugin_upload
    ADD CONSTRAINT pk_plugin_upload PRIMARY KEY (sha256);

ALTER TABLE ONLY plugin_upload
    ADD CONSTRAINT fk_plugin_upload_sha256 FOREIGN KEY (sha256) REFERENCES plugin_artifact(sha256);
--rollback DROP TABLE IF EXISTS plugin_upload CASCADE;
