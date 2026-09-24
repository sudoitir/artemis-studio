--liquibase formatted sql

-- Runtime plugins (ADR-0103): the installer tier, checked on every admin request rather than
-- cached in a session, so revoking it takes effect immediately. It has no seed row here — the
-- first installer is granted at boot from the bootstrap administrator or
-- artemis-studio.plugins.initial-installers. The foreign key to app_user is allowed because
-- kernel/plugin's changelog is included after kernel/security's (SchemaOwnershipTest); the Java
-- module dependency runs the other way (kernel.security depends on kernel.plugin, ADR-0069), so
-- this is a plain SQL foreign key, never a Java import. Never edit this file once released; add a
-- new changeset beside it.

--changeset artemis-studio:kernel-plugin-0003-plugin-installer
CREATE TABLE plugin_installer (
    granted_at timestamp with time zone NOT NULL,
    granted_by text,
    user_id uuid NOT NULL
);

ALTER TABLE ONLY plugin_installer
    ADD CONSTRAINT pk_plugin_installer PRIMARY KEY (user_id);

ALTER TABLE ONLY plugin_installer
    ADD CONSTRAINT fk_plugin_installer_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS plugin_installer CASCADE;
