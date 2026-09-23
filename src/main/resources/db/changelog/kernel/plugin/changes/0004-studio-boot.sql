--liquibase formatted sql

-- Runtime plugins (ADR-0099): the crash-loop guard. A boot that never reaches a clean stop,
-- three times in fifteen minutes, trips safe mode (no plugin starts). Never edit this file once
-- released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0004-studio-boot
CREATE TABLE studio_boot (
    started_at timestamp with time zone NOT NULL,
    stopped_at timestamp with time zone,
    id bigint NOT NULL
);

ALTER TABLE studio_boot ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME studio_boot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY studio_boot
    ADD CONSTRAINT pk_studio_boot PRIMARY KEY (id);

CREATE INDEX ix_studio_boot_started ON studio_boot USING btree (started_at DESC);
--rollback DROP TABLE IF EXISTS studio_boot CASCADE;
