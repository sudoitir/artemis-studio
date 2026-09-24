--liquibase formatted sql
-- Runtime plugins (ADR-0103): an installation that already has accounts gets its first installer
-- here — the earliest local account, which is the administrator Studio created on first boot. A
-- fresh installation has no account yet at this point; its administrator is granted when it is
-- created. Never edit this file once released; add a new changeset beside it.
--changeset artemis-studio:kernel-plugin-0007-plugin-installer-seed
INSERT INTO plugin_installer (granted_at, granted_by, user_id)
SELECT now(), 'bootstrap', id FROM app_user WHERE provider_id = 'local' ORDER BY created_at LIMIT 1;
--rollback DELETE FROM plugin_installer WHERE granted_by = 'bootstrap';
