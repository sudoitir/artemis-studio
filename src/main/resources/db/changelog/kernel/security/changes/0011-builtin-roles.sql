--liquibase formatted sql

-- The built-in roles are rebuilt for resource-scoped permissions (authorization spec): the
-- permissions of ADMIN, OPERATOR and VIEWER are replaced, and the team roles TEAM_VIEWER,
-- TEAM_OPERATOR and TEAM_ADMIN are added. A role's permissions are re-seeded rather than migrated,
-- so a built-in role holds exactly what is written here. BuiltInRolesCoverageTest keeps this list
-- complete: a new core permission must be added to a role below, or listed there as excluded.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0011-builtin-roles
INSERT INTO role (name, builtin, team_assignable)
VALUES ('TEAM_VIEWER', TRUE, TRUE), ('TEAM_OPERATOR', TRUE, TRUE), ('TEAM_ADMIN', TRUE, TRUE);

DELETE FROM role_permission WHERE role_id IN (SELECT id FROM role WHERE builtin);

INSERT INTO role_permission (role_id, action)
SELECT r.id, p.action FROM role r
JOIN (VALUES
    ('ADMIN', '*'),

    -- Every read permission, and nothing that changes anything.
    ('VIEWER', 'cluster:read'), ('VIEWER', 'queue:read'), ('VIEWER', 'address:read'),
    ('VIEWER', 'message:read'), ('VIEWER', 'connection:read'), ('VIEWER', 'alert:read'),
    ('VIEWER', 'environment:read'), ('VIEWER', 'settings:read'), ('VIEWER', 'governance:read'),
    ('VIEWER', 'data:read'),

    -- Viewer, and every cluster and resource permission that operates brokers: none that edits
    -- broker configuration, Studio settings or credentials, users, roles, teams, tokens, plugins
    -- or data, and not the sensitive values shown in clear.
    ('OPERATOR', 'cluster:read'), ('OPERATOR', 'queue:read'), ('OPERATOR', 'address:read'),
    ('OPERATOR', 'message:read'), ('OPERATOR', 'connection:read'), ('OPERATOR', 'alert:read'),
    ('OPERATOR', 'environment:read'), ('OPERATOR', 'settings:read'), ('OPERATOR', 'governance:read'),
    ('OPERATOR', 'data:read'),
    ('OPERATOR', 'cluster:write'), ('OPERATOR', 'alert:write'), ('OPERATOR', 'connection:close'),
    ('OPERATOR', 'rr:write'), ('OPERATOR', 'queue:create'), ('OPERATOR', 'queue:update'),
    ('OPERATOR', 'queue:delete'), ('OPERATOR', 'queue:pause'), ('OPERATOR', 'queue:purge'),
    ('OPERATOR', 'message:send'), ('OPERATOR', 'message:move'), ('OPERATOR', 'message:delete'),
    ('OPERATOR', 'address:create'), ('OPERATOR', 'divert:write'), ('OPERATOR', 'capture:write'),

    ('TEAM_VIEWER', 'queue:read'), ('TEAM_VIEWER', 'address:read'), ('TEAM_VIEWER', 'message:read'),

    ('TEAM_OPERATOR', 'queue:read'), ('TEAM_OPERATOR', 'address:read'), ('TEAM_OPERATOR', 'message:read'),
    ('TEAM_OPERATOR', 'message:send'), ('TEAM_OPERATOR', 'message:move'), ('TEAM_OPERATOR', 'message:delete'),
    ('TEAM_OPERATOR', 'queue:purge'), ('TEAM_OPERATOR', 'queue:create'), ('TEAM_OPERATOR', 'queue:update'),
    ('TEAM_OPERATOR', 'queue:delete'), ('TEAM_OPERATOR', 'queue:pause'), ('TEAM_OPERATOR', 'address:create'),
    ('TEAM_OPERATOR', 'divert:write'),

    ('TEAM_ADMIN', 'queue:read'), ('TEAM_ADMIN', 'address:read'), ('TEAM_ADMIN', 'message:read'),
    ('TEAM_ADMIN', 'message:send'), ('TEAM_ADMIN', 'message:move'), ('TEAM_ADMIN', 'message:delete'),
    ('TEAM_ADMIN', 'queue:purge'), ('TEAM_ADMIN', 'queue:create'), ('TEAM_ADMIN', 'queue:update'),
    ('TEAM_ADMIN', 'queue:delete'), ('TEAM_ADMIN', 'queue:pause'), ('TEAM_ADMIN', 'address:create'),
    ('TEAM_ADMIN', 'divert:write'), ('TEAM_ADMIN', 'team:admin')
) AS p(role_name, action) ON r.name = p.role_name AND r.builtin;
--rollback DELETE FROM role WHERE name IN ('TEAM_VIEWER', 'TEAM_OPERATOR', 'TEAM_ADMIN') AND builtin;
