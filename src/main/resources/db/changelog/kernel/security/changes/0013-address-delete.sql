--liquibase formatted sql

-- Destroying an address is its own permission, address:delete, held on an address: queue:delete acts
-- on queues, and a team that owns queue names does not thereby own the addresses behind them. The
-- built-in roles that destroy queues may destroy addresses too (authorization spec).
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0013-address-delete
INSERT INTO role_permission (role_id, action)
SELECT r.id, 'address:delete' FROM role r
WHERE r.builtin AND r.name IN ('OPERATOR', 'TEAM_OPERATOR', 'TEAM_ADMIN');
--rollback DELETE FROM role_permission WHERE action = 'address:delete' AND role_id IN (SELECT id FROM role WHERE builtin);
