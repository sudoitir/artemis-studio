--liquibase formatted sql

-- The feature/apitokens module's schema as of the per-module re-baseline (ADR-0072), generated
-- from a database migrated by the previous changelog and split by owner. Column order
-- (non-negotiable #7), storage parameters, partitions and indexes are as they were.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-apitokens-0001-baseline splitStatements:true
CREATE TABLE api_token (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    name text NOT NULL,
    prefix text NOT NULL,
    token_hash bytea NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE api_token_grant (
    action text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    token_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_api_token_grant_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

ALTER TABLE ONLY api_token
    ADD CONSTRAINT pk_api_token PRIMARY KEY (id);

ALTER TABLE ONLY api_token_grant
    ADD CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id);

ALTER TABLE ONLY api_token
    ADD CONSTRAINT uq_api_token_prefix UNIQUE (prefix);

CREATE INDEX ix_api_token_user ON api_token USING btree (user_id);

ALTER TABLE ONLY api_token_grant
    ADD CONSTRAINT fk_api_token_grant_token FOREIGN KEY (token_id) REFERENCES api_token(id) ON DELETE CASCADE;

ALTER TABLE ONLY api_token
    ADD CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS api_token_grant CASCADE;
--rollback DROP TABLE IF EXISTS api_token CASCADE;
