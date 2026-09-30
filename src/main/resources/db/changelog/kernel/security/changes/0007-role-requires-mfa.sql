--liquibase formatted sql

-- A role can require a second factor of the local accounts that hold it (ADR-0142). The built-in
-- administrator role requires one by default; every other role opts in.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0007-role-requires-mfa
ALTER TABLE role ADD COLUMN requires_mfa boolean DEFAULT false NOT NULL;
UPDATE role SET requires_mfa = true WHERE name = 'ADMIN' AND builtin;
--rollback ALTER TABLE role DROP COLUMN requires_mfa;
