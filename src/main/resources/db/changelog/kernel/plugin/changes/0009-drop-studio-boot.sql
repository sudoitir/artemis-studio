--liquibase formatted sql

-- The crash-loop guard counts crashes recorded in studio_replica (kernel-replica 0001) instead of
-- boots, so a healthy second replica is never mistaken for a crash. Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0009-drop-studio-boot
DROP TABLE studio_boot;
--rollback CREATE TABLE studio_boot (started_at timestamp with time zone NOT NULL, stopped_at timestamp with time zone, id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
