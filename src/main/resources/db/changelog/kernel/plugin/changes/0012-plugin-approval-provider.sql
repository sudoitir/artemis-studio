--liquibase formatted sql

-- Whether the installed version declares itself the approval provider (ADR-0179). The approval gate
-- is armed while a row with this flag is meant to be active, which is one read of the partial index
-- below. Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0012-plugin-approval-provider
ALTER TABLE plugin_install ADD COLUMN approval_provider boolean NOT NULL DEFAULT false;
CREATE INDEX ix_plugin_install_approval_provider ON plugin_install (id) WHERE approval_provider;
--rollback DROP INDEX IF EXISTS ix_plugin_install_approval_provider;
--rollback ALTER TABLE plugin_install DROP COLUMN IF EXISTS approval_provider;
