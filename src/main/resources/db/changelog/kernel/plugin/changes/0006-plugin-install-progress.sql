--liquibase formatted sql

-- Runtime plugins (ADR-0099..0103, task 6.8): the current activation step, for the admin UI to
-- poll while PluginHost.activate() runs on its own virtual thread. Column order follows the
-- padding rule (8-byte step_started_at before 4-byte progress) even though Postgres always
-- appends new columns after existing ones physically. Never edit this file once released; add a
-- new changeset beside it.

--changeset artemis-studio:kernel-plugin-0006-plugin-install-progress
ALTER TABLE plugin_install ADD COLUMN step_started_at timestamp with time zone;
ALTER TABLE plugin_install ADD COLUMN progress text;
--rollback ALTER TABLE plugin_install DROP COLUMN IF EXISTS progress;
--rollback ALTER TABLE plugin_install DROP COLUMN IF EXISTS step_started_at;
