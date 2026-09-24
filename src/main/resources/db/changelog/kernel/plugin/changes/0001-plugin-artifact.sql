--liquibase formatted sql

-- Runtime plugins (ADR-0099): the content-addressed jar store. The same bytes are never stored
-- twice, keyed by sha256. STORAGE EXTERNAL because a jar is already compressed, so TOAST
-- compression would only cost CPU. Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0001-plugin-artifact
CREATE TABLE plugin_artifact (
    uploaded_at timestamp with time zone NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 text NOT NULL,
    content bytea NOT NULL
);

ALTER TABLE ONLY plugin_artifact
    ADD CONSTRAINT pk_plugin_artifact PRIMARY KEY (sha256);

ALTER TABLE plugin_artifact ALTER COLUMN content SET STORAGE EXTERNAL;
--rollback DROP TABLE IF EXISTS plugin_artifact CASCADE;
