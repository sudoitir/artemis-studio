--liquibase formatted sql

-- Phase 8 governance. `003-identity.sql` (released — never edited) already
-- shipped app_user/role/role_permission/user_role for local accounts and
-- scoped RBAC; this file adds what Phase 8 actually needs on top: OIDC
-- identity fields, a builtin-role flag, API tokens with their own scoped
-- grants, OIDC claim->role mapping, and the Spring Session JDBC store
-- (ADR-0037, ADR-0038, ADR-0039, ADR-0040).

--changeset artemis-studio:014-app-user-oidc
--comment: OIDC-sourced accounts are keyed by issuer+subject and carry no
--         password (003's password_hash comment already anticipated this).
--         must_change_password gates the bootstrap admin until it is rotated.
ALTER TABLE app_user
    ADD COLUMN issuer               TEXT,
    ADD COLUMN subject               TEXT,
    ADD COLUMN auth_source           TEXT NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN must_change_password  BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_auth_source CHECK (auth_source IN ('LOCAL', 'OIDC'));
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_issuer_subject UNIQUE (issuer, subject);
--rollback ALTER TABLE app_user DROP CONSTRAINT uq_app_user_issuer_subject;
--rollback ALTER TABLE app_user DROP CONSTRAINT ck_app_user_auth_source;
--rollback ALTER TABLE app_user DROP COLUMN must_change_password;
--rollback ALTER TABLE app_user DROP COLUMN auth_source;
--rollback ALTER TABLE app_user DROP COLUMN subject;
--rollback ALTER TABLE app_user DROP COLUMN issuer;

--changeset artemis-studio:014-role-builtin
--comment: ADMIN/OPERATOR/VIEWER are immutable (design.md decision 4); custom
--         roles are not.
ALTER TABLE role ADD COLUMN builtin BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE role DROP COLUMN builtin;

--changeset artemis-studio:014-api-token
--comment: personal API tokens (ADR-0039). token_hash is SHA-256 of the
--         secret half, looked up by the indexed prefix, never the reverse -
--         see AdrApiTokenService. Not encrypted with SecretVault: a token
--         secret is 256 bits of random entropy, not a rotatable credential.
CREATE TABLE api_token (
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ,
    last_used_at   TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    name           TEXT  NOT NULL,
    prefix         TEXT  NOT NULL,
    token_hash     BYTEA NOT NULL,
    id             UUID  NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID  NOT NULL,
    CONSTRAINT pk_api_token PRIMARY KEY (id),
    CONSTRAINT fk_api_token_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT uq_api_token_prefix UNIQUE (prefix)
);
CREATE INDEX ix_api_token_user ON api_token (user_id);
--rollback DROP TABLE api_token;

--changeset artemis-studio:014-api-token-grant
--comment: mirrors user_role's scope shape (global/environment/cluster); a
--         token's grants are intersected with its owner's live grants at
--         auth time (design.md decision 5), never trusted alone.
CREATE TABLE api_token_grant (
    action      TEXT NOT NULL,
    scope_type  TEXT NOT NULL DEFAULT 'GLOBAL',
    token_id    UUID NOT NULL,
    scope_id    UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id),
    CONSTRAINT fk_api_token_grant_token FOREIGN KEY (token_id)
        REFERENCES api_token (id) ON DELETE CASCADE,
    CONSTRAINT ck_api_token_grant_scope CHECK (scope_type IN ('GLOBAL', 'ENVIRONMENT', 'CLUSTER'))
);
--rollback DROP TABLE api_token_grant;

--changeset artemis-studio:014-oidc-role-mapping
--comment: claim value -> role grant, re-applied on every OIDC login
--         (ADR-0040); editable in the admin UI.
CREATE TABLE oidc_role_mapping (
    claim        TEXT NOT NULL,
    claim_value  TEXT NOT NULL,
    scope_type   TEXT NOT NULL DEFAULT 'GLOBAL',
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    role_id      UUID NOT NULL,
    scope_id     UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CONSTRAINT pk_oidc_role_mapping PRIMARY KEY (id),
    CONSTRAINT fk_oidc_role_mapping_role FOREIGN KEY (role_id)
        REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT ck_oidc_role_mapping_scope CHECK (scope_type IN ('GLOBAL', 'ENVIRONMENT', 'CLUSTER')),
    CONSTRAINT uq_oidc_role_mapping UNIQUE (claim, claim_value, role_id, scope_type, scope_id)
);
--rollback DROP TABLE oidc_role_mapping;

--changeset artemis-studio:014-builtin-role-seed
--comment: seed the three built-in roles and their permissions
--         (security/Permissions.java is the authoritative catalogue; these
--         string values must match it). ADMIN holds the full wildcard.
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
--rollback DELETE FROM role_permission WHERE role_id IN (SELECT id FROM role WHERE builtin);
--rollback DELETE FROM role WHERE name IN ('ADMIN', 'OPERATOR', 'VIEWER') AND builtin;

--changeset artemis-studio:014-spring-session runInTransaction:false
--comment: Spring Session JDBC store (spring-session-jdbc 4.x), schema owned
--         here rather than the library's own initializer
--         (spring.session.jdbc.initialize-schema=never) so Liquibase stays
--         the single source of schema truth (ADR-0011). DDL from the
--         upstream reference's PostgreSQL schema script.
CREATE TABLE spring_session (
    creation_time          BIGINT      NOT NULL,
    last_access_time       BIGINT      NOT NULL,
    max_inactive_interval  INT         NOT NULL,
    expiry_time            BIGINT      NOT NULL,
    principal_name         VARCHAR(100),
    primary_id             CHAR(36)    NOT NULL,
    session_id             CHAR(36)    NOT NULL,
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  CHAR(36)     NOT NULL,
    attribute_name       VARCHAR(200) NOT NULL,
    attribute_bytes       BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
--rollback DROP TABLE spring_session_attributes;
--rollback DROP TABLE spring_session;
