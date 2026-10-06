--liquibase formatted sql

-- Teams: groups of users and directory groups that own queue and address name patterns on a
-- cluster, hold a team role over what the patterns match, and can share part of it with another
-- team (team-access spec). A team-assignable role is a role that may be used as a team role.
-- A cluster id is not a foreign key: clusters live in platform/clusters, which this module may not
-- depend on, so ScopedGrants deletes a deleted cluster's rows, as it does for grants scoped to it.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-security-0010-teams
ALTER TABLE role ADD COLUMN team_assignable boolean DEFAULT false NOT NULL;

CREATE TABLE team (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    name text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);

ALTER TABLE ONLY team
    ADD CONSTRAINT pk_team PRIMARY KEY (id);

CREATE UNIQUE INDEX uq_team_name_lower ON team (lower(name));

-- A name pattern a team owns on one cluster. kind says which names it covers.
CREATE TABLE team_pattern (
    kind text NOT NULL,
    pattern text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    team_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_team_pattern_kind CHECK (kind IN ('QUEUE', 'ADDRESS', 'BOTH'))
);

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT pk_team_pattern PRIMARY KEY (id);

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT uq_team_pattern UNIQUE (team_id, cluster_id, kind, pattern);

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT fk_team_pattern_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE;

CREATE INDEX ix_team_pattern_cluster ON team_pattern USING btree (cluster_id);

-- A user, or a directory group of one identity provider, holding one team role in a team.
CREATE TABLE team_member (
    principal_type text NOT NULL,
    provider_id text,
    group_name text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid,
    role_id uuid NOT NULL,
    CONSTRAINT ck_team_member_principal CHECK (
        (principal_type = 'USER' AND user_id IS NOT NULL AND provider_id IS NULL AND group_name IS NULL)
        OR (principal_type = 'GROUP' AND user_id IS NULL AND provider_id IS NOT NULL AND group_name IS NOT NULL))
);

ALTER TABLE ONLY team_member
    ADD CONSTRAINT pk_team_member PRIMARY KEY (id);

CREATE UNIQUE INDEX uq_team_member_user ON team_member (team_id, user_id) WHERE principal_type = 'USER';

CREATE UNIQUE INDEX uq_team_member_group ON team_member (team_id, provider_id, group_name)
    WHERE principal_type = 'GROUP';

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

CREATE INDEX ix_team_member_user ON team_member USING btree (user_id) WHERE user_id IS NOT NULL;

-- A pattern inside what the owner team owns, shared with another team at a team role.
CREATE TABLE team_share (
    kind text NOT NULL,
    pattern text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    owner_team_id uuid NOT NULL,
    target_team_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    role_id uuid NOT NULL,
    CONSTRAINT ck_team_share_kind CHECK (kind IN ('QUEUE', 'ADDRESS', 'BOTH')),
    CONSTRAINT ck_team_share_distinct CHECK (owner_team_id <> target_team_id)
);

ALTER TABLE ONLY team_share
    ADD CONSTRAINT pk_team_share PRIMARY KEY (id);

ALTER TABLE ONLY team_share
    ADD CONSTRAINT uq_team_share UNIQUE (owner_team_id, target_team_id, cluster_id, kind, pattern);

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_owner FOREIGN KEY (owner_team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_target FOREIGN KEY (target_team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

CREATE INDEX ix_team_share_cluster ON team_share USING btree (cluster_id);
--rollback DROP TABLE IF EXISTS team_share CASCADE;
--rollback DROP TABLE IF EXISTS team_member CASCADE;
--rollback DROP TABLE IF EXISTS team_pattern CASCADE;
--rollback DROP TABLE IF EXISTS team CASCADE;
--rollback ALTER TABLE role DROP COLUMN team_assignable;
