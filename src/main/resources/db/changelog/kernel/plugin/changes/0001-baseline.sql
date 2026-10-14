--liquibase formatted sql

-- The kernel/plugin module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-plugin-0001-baseline splitStatements:true
CREATE TABLE plugin_artifact (
    uploaded_at timestamp with time zone NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 text NOT NULL,
    content bytea NOT NULL
);
ALTER TABLE ONLY plugin_artifact ALTER COLUMN content SET STORAGE EXTERNAL;

CREATE TABLE plugin_install (
    installed_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    step_started_at timestamp with time zone,
    id text NOT NULL,
    version text NOT NULL,
    vendor text NOT NULL,
    sha256 text NOT NULL,
    previous_sha256 text,
    status text NOT NULL,
    failure text,
    installed_by text,
    descriptor jsonb NOT NULL,
    progress text,
    signer_fingerprint text,
    signer_subject text,
    schema_changed boolean DEFAULT false NOT NULL,
    approval_provider boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_plugin_install_status CHECK ((status = ANY (ARRAY['activating'::text, 'active'::text, 'disabled'::text, 'failed'::text, 'incompatible'::text, 'needs_restart'::text, 'uninstalled'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE plugin_installer (
    granted_at timestamp with time zone NOT NULL,
    granted_by text,
    user_id uuid NOT NULL
);

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
    CONSTRAINT ck_plugin_license_size CHECK (((size >= 1) AND (size <= 65536)))
);

CREATE TABLE plugin_trust_policy (
    changed_at timestamp with time zone,
    id integer NOT NULL,
    changed_by text,
    allow_unverified boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_plugin_trust_policy_single_row CHECK ((id = 1))
);

CREATE TABLE plugin_trusted_key (
    added_at timestamp with time zone DEFAULT now() NOT NULL,
    fingerprint text NOT NULL,
    name text NOT NULL,
    subject text NOT NULL,
    public_key bytea NOT NULL,
    added_by text NOT NULL,
    source text DEFAULT 'ADMIN'::text NOT NULL,
    CONSTRAINT ck_plugin_trusted_key_source CHECK ((source = ANY (ARRAY['ADMIN'::text, 'CONFIGURATION'::text])))
);

CREATE TABLE plugin_upload (
    uploaded_at timestamp with time zone NOT NULL,
    sha256 text NOT NULL,
    plugin_id text NOT NULL,
    uploaded_by text NOT NULL,
    descriptor jsonb NOT NULL,
    report jsonb NOT NULL
);

ALTER TABLE ONLY plugin_artifact
    ADD CONSTRAINT pk_plugin_artifact PRIMARY KEY (sha256);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT pk_plugin_install PRIMARY KEY (id);

ALTER TABLE ONLY plugin_installer
    ADD CONSTRAINT pk_plugin_installer PRIMARY KEY (user_id);

ALTER TABLE ONLY plugin_license
    ADD CONSTRAINT pk_plugin_license PRIMARY KEY (plugin_id);

ALTER TABLE ONLY plugin_trust_policy
    ADD CONSTRAINT pk_plugin_trust_policy PRIMARY KEY (id);

ALTER TABLE ONLY plugin_trusted_key
    ADD CONSTRAINT pk_plugin_trusted_key PRIMARY KEY (fingerprint);

ALTER TABLE ONLY plugin_upload
    ADD CONSTRAINT pk_plugin_upload PRIMARY KEY (sha256);

CREATE INDEX ix_plugin_install_approval_provider ON plugin_install USING btree (id) WHERE approval_provider;

CREATE INDEX ix_plugin_install_previous_sha256 ON plugin_install USING btree (previous_sha256);

CREATE INDEX ix_plugin_install_sha256 ON plugin_install USING btree (sha256);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT fk_plugin_install_previous_sha256 FOREIGN KEY (previous_sha256) REFERENCES plugin_artifact(sha256);

ALTER TABLE ONLY plugin_install
    ADD CONSTRAINT fk_plugin_install_sha256 FOREIGN KEY (sha256) REFERENCES plugin_artifact(sha256);

ALTER TABLE ONLY plugin_installer
    ADD CONSTRAINT fk_plugin_installer_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY plugin_upload
    ADD CONSTRAINT fk_plugin_upload_sha256 FOREIGN KEY (sha256) REFERENCES plugin_artifact(sha256);

INSERT INTO plugin_trust_policy (id) VALUES (1);
--rollback DROP TABLE IF EXISTS plugin_upload CASCADE;
--rollback DROP TABLE IF EXISTS plugin_trusted_key CASCADE;
--rollback DROP TABLE IF EXISTS plugin_trust_policy CASCADE;
--rollback DROP TABLE IF EXISTS plugin_license CASCADE;
--rollback DROP TABLE IF EXISTS plugin_installer CASCADE;
--rollback DROP TABLE IF EXISTS plugin_install CASCADE;
--rollback DROP TABLE IF EXISTS plugin_artifact CASCADE;
