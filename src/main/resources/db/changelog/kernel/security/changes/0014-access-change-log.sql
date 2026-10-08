--liquibase formatted sql

-- Who changed what anyone may do, and to whom (ADR-0181): one row per access change, written by
-- AccessChanges in the caller's transaction. `subject_id` is the user whose access changed, null for
-- a change that reaches everyone (a role, a team, a mapping). The approval rules ask whether an actor
-- changed anyone else's access since a time. The data lifecycle purges it (kernel/lifecycle
-- AccessChangeLogStore). Never edit this file once released.

--changeset artemis-studio:kernel-security-0014-access-change-log
CREATE TABLE access_change_log (
    at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    actor_id uuid NOT NULL,
    subject_id uuid
);

ALTER TABLE ONLY access_change_log
    ADD CONSTRAINT pk_access_change_log PRIMARY KEY (id);

CREATE INDEX ix_access_change_log_actor_at ON access_change_log USING btree (actor_id, at);
CREATE INDEX ix_access_change_log_at ON access_change_log USING btree (at);
--rollback DROP TABLE IF EXISTS access_change_log;
