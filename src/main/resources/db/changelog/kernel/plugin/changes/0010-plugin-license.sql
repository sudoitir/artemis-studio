--liquibase formatted sql

-- A plugin's license file and the verdict the plugin reported for it: one row per plugin id. Studio
-- stores the file as opaque bytes; the plugin judges it and reports status, expiry, licensee and a
-- short detail, which stay empty until it does. Postgres is shared, so every replica serves the same
-- file and verdict. Deleted when the plugin is purged, kept when it is uninstalled. Not encrypted at
-- rest: a license is signed, not secret. Column order follows the padding rule (8-byte first, then
-- 4-byte). Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0010-plugin-license
CREATE TABLE plugin_license (
    uploaded_at timestamp with time zone NOT NULL,
    reported_at timestamp with time zone,
    expires_at timestamp with time zone,
    size integer NOT NULL,
    content bytea NOT NULL,
    sha256 text NOT NULL,
    uploaded_by text NOT NULL,
    status text,
    licensee text,
    detail text,
    plugin_id text NOT NULL,
    CONSTRAINT ck_plugin_license_size CHECK (size BETWEEN 1 AND 65536)
);

ALTER TABLE ONLY plugin_license
    ADD CONSTRAINT pk_plugin_license PRIMARY KEY (plugin_id);
--rollback DROP TABLE IF EXISTS plugin_license;
