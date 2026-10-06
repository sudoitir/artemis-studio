--liquibase formatted sql

-- The directory groups an external user was in at their last sign-in. They are replaced at every
-- external sign-in, so a team that holds a group as a member reaches the users in it, and a change
-- in the directory applies at the next sign-in (team-access spec).
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0012-user-group
CREATE TABLE user_group (
    provider_id text NOT NULL,
    group_name text NOT NULL,
    user_id uuid NOT NULL
);

ALTER TABLE ONLY user_group
    ADD CONSTRAINT pk_user_group PRIMARY KEY (user_id, provider_id, group_name);

ALTER TABLE ONLY user_group
    ADD CONSTRAINT fk_user_group_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

CREATE INDEX ix_user_group_group ON user_group USING btree (provider_id, group_name);
--rollback DROP TABLE IF EXISTS user_group CASCADE;
