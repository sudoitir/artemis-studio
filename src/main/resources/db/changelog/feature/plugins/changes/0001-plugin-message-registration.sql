--liquibase formatted sql

-- Plugins' message registrations (ADR-0111): what each plugin wants tapped or consumed, acting for
-- which user, and what the last reconciliation found on each node. The reconciler converges broker
-- objects and drains onto these rows. No foreign keys into cluster or app_user: a registration
-- whose cluster or user is gone is reported as such, not cascaded away under the plugin.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-plugins-0001-plugin-message-registration
CREATE TABLE plugin_message_registration (
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    plugin_id text NOT NULL,
    reg_key text NOT NULL,
    queue_name text NOT NULL,
    mode text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    acting_user_id uuid NOT NULL,
    CONSTRAINT ck_plugin_message_registration_mode CHECK ((mode = ANY (ARRAY['TAP'::text, 'CONSUME'::text])))
);

ALTER TABLE ONLY plugin_message_registration
    ADD CONSTRAINT pk_plugin_message_registration PRIMARY KEY (id);

ALTER TABLE ONLY plugin_message_registration
    ADD CONSTRAINT uq_plugin_message_registration_key UNIQUE (plugin_id, reg_key);

-- Rewritten on every pass that changes something, so kept small and vacuumed often.
CREATE TABLE plugin_message_registration_node (
    updated_at timestamp with time zone NOT NULL,
    dropped_copies bigint,
    state text NOT NULL,
    detail text,
    registration_id uuid NOT NULL,
    node_id uuid NOT NULL
) WITH (fillfactor = 80, autovacuum_vacuum_scale_factor = 0.05);

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT pk_plugin_message_registration_node PRIMARY KEY (registration_id, node_id);

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT fk_plugin_message_registration_node_registration FOREIGN KEY (registration_id)
        REFERENCES plugin_message_registration(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS plugin_message_registration_node CASCADE;
--rollback DROP TABLE IF EXISTS plugin_message_registration CASCADE;
