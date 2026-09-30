--liquibase formatted sql

-- Usernames differing only in case ("Admin" and "admin") are one name to a person reading an
-- audit trail or a grant list, so the database refuses the second one.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0008-username-unique-ignoring-case
CREATE UNIQUE INDEX uq_app_user_username_lower ON app_user (lower(username));
--rollback DROP INDEX uq_app_user_username_lower;
