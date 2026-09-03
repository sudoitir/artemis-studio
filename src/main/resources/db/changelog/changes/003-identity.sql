--liquibase formatted sql

-- People and permission. Local users for the MVP; OIDC accounts (password_hash
-- null) arrive in v1.0. RBAC is role -> permissions, assigned to users at a
-- scope (global / environment / cluster).

--changeset artemis-studio:003-identity-app-user
CREATE TABLE app_user (
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    username       TEXT NOT NULL,
    email          TEXT,
    password_hash  TEXT,                   -- null for OIDC-only accounts
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    disabled       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);
--rollback DROP TABLE app_user;

--changeset artemis-studio:003-identity-role
CREATE TABLE role (
    name  TEXT NOT NULL,                   -- ADMIN | OPERATOR | VIEWER
    id    UUID NOT NULL DEFAULT gen_random_uuid(),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uq_role_name UNIQUE (name)
);
--rollback DROP TABLE role;

--changeset artemis-studio:003-identity-role-permission
CREATE TABLE role_permission (
    action   TEXT NOT NULL,                -- QUEUE_PURGE, MESSAGE_SEND, MESSAGE_MOVE, ...
    role_id  UUID NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, action),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id)
        REFERENCES role (id) ON DELETE CASCADE
);
--rollback DROP TABLE role_permission;

--changeset artemis-studio:003-identity-user-role
CREATE TABLE user_role (
    scope_type  TEXT NOT NULL DEFAULT 'GLOBAL',   -- GLOBAL | ENVIRONMENT | CLUSTER
    user_id     UUID NOT NULL,
    role_id     UUID NOT NULL,
    -- nil UUID for GLOBAL scope: keeps scope_id in the primary key (PK columns
    -- cannot be null) without a separate COALESCE unique index.
    scope_id    UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id, scope_type, scope_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id)
        REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_role_scope CHECK (scope_type IN ('GLOBAL', 'ENVIRONMENT', 'CLUSTER'))
);
--rollback DROP TABLE user_role;
