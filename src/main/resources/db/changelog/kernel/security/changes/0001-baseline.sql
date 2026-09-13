--liquibase formatted sql

-- The kernel/security module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0001-baseline splitStatements:true
CREATE TABLE app_user (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    username text NOT NULL,
    email text,
    password_hash text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    disabled boolean DEFAULT false NOT NULL,
    issuer text,
    subject text,
    auth_source text DEFAULT 'LOCAL'::text NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_app_user_auth_source CHECK ((auth_source = ANY (ARRAY['LOCAL'::text, 'OIDC'::text])))
);

CREATE TABLE oidc_role_mapping (
    claim text NOT NULL,
    claim_value text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_oidc_role_mapping_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

CREATE TABLE role (
    name text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    builtin boolean DEFAULT false NOT NULL
);

CREATE TABLE role_permission (
    action text NOT NULL,
    role_id uuid NOT NULL
);

CREATE TABLE spring_session (
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100),
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL
);

CREATE TABLE spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);

CREATE TABLE user_role (
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_user_role_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT pk_app_user PRIMARY KEY (id);

ALTER TABLE ONLY oidc_role_mapping
    ADD CONSTRAINT pk_oidc_role_mapping PRIMARY KEY (id);

ALTER TABLE ONLY role
    ADD CONSTRAINT pk_role PRIMARY KEY (id);

ALTER TABLE ONLY role_permission
    ADD CONSTRAINT pk_role_permission PRIMARY KEY (role_id, action);

ALTER TABLE ONLY user_role
    ADD CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id, scope_type, scope_id);

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);

ALTER TABLE ONLY spring_session
    ADD CONSTRAINT spring_session_pk PRIMARY KEY (primary_id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT uq_app_user_issuer_subject UNIQUE (issuer, subject);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT uq_app_user_username UNIQUE (username);

ALTER TABLE ONLY oidc_role_mapping
    ADD CONSTRAINT uq_oidc_role_mapping UNIQUE (claim, claim_value, role_id, scope_type, scope_id);

ALTER TABLE ONLY role
    ADD CONSTRAINT uq_role_name UNIQUE (name);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session USING btree (session_id);

CREATE INDEX spring_session_ix2 ON spring_session USING btree (expiry_time);

CREATE INDEX spring_session_ix3 ON spring_session USING btree (principal_name);

ALTER TABLE ONLY oidc_role_mapping
    ADD CONSTRAINT fk_oidc_role_mapping_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY role_permission
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_role
    ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_role
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE;

-- The built-in roles and their permissions (authorization spec). ADMIN holds the full wildcard.
INSERT INTO role (name, builtin)
VALUES ('ADMIN', TRUE), ('OPERATOR', TRUE), ('VIEWER', TRUE);

INSERT INTO role_permission (role_id, action)
SELECT id, '*' FROM role WHERE name = 'ADMIN';

INSERT INTO role_permission (role_id, action)
SELECT r.id, p.action FROM role r
CROSS JOIN (VALUES
    ('cluster:read'), ('cluster:write'), ('environment:read'),
    ('message:read'), ('message:send'), ('message:move'), ('message:delete'),
    ('queue:purge'), ('alert:read'), ('alert:write'), ('settings:read')
) AS p(action)
WHERE r.name = 'OPERATOR';

INSERT INTO role_permission (role_id, action)
SELECT r.id, p.action FROM role r
CROSS JOIN (VALUES
    ('cluster:read'), ('environment:read'), ('message:read'),
    ('alert:read'), ('settings:read')
) AS p(action)
WHERE r.name = 'VIEWER';

--rollback DROP TABLE IF EXISTS spring_session_attributes CASCADE;
--rollback DROP TABLE IF EXISTS spring_session CASCADE;
--rollback DROP TABLE IF EXISTS oidc_role_mapping CASCADE;
--rollback DROP TABLE IF EXISTS user_role CASCADE;
--rollback DROP TABLE IF EXISTS role_permission CASCADE;
--rollback DROP TABLE IF EXISTS role CASCADE;
--rollback DROP TABLE IF EXISTS app_user CASCADE;
