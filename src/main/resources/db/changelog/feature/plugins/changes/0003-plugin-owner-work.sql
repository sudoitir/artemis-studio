--liquibase formatted sql

-- Work a plugin runs later as its owner (plugin-runtime spec): which user it runs for, the permissions on
-- clusters, queues and addresses it needs (a JSON list), and whether Studio let it run at its last
-- check. A suspended row states why, and stays suspended until someone enables it again. No foreign key
-- into app_user: work whose owner is gone is reported as suspended, not cascaded away under the plugin.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-plugins-0003-plugin-owner-work
CREATE TABLE plugin_owner_work (
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    plugin_id text NOT NULL,
    work_key text NOT NULL,
    needs text NOT NULL,
    state text NOT NULL,
    reason text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    owner_user_id uuid NOT NULL,
    CONSTRAINT ck_plugin_owner_work_state CHECK ((state = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text])))
);

ALTER TABLE ONLY plugin_owner_work
    ADD CONSTRAINT pk_plugin_owner_work PRIMARY KEY (id);

ALTER TABLE ONLY plugin_owner_work
    ADD CONSTRAINT uq_plugin_owner_work_key UNIQUE (plugin_id, work_key);
--rollback DROP TABLE IF EXISTS plugin_owner_work CASCADE;
