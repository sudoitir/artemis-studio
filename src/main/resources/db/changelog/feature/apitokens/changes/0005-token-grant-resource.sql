--liquibase formatted sql

-- A token grant may be limited to the queues or addresses of its scope whose names match a pattern
-- (api-tokens spec). An empty kind and pattern is a grant on the whole scope, as every grant was; empty
-- rather than null so that the two stay part of the primary key. Never edit this file once released;
-- add a new changeset beside it.

--changeset artemis-studio:feature-apitokens-0005-token-grant-resource splitStatements:true
ALTER TABLE api_token_grant ADD COLUMN resource_kind text DEFAULT '' NOT NULL;
ALTER TABLE api_token_grant ADD COLUMN resource_pattern text DEFAULT '' NOT NULL;

ALTER TABLE api_token_grant DROP CONSTRAINT pk_api_token_grant;
ALTER TABLE api_token_grant
    ADD CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id, resource_kind, resource_pattern);

ALTER TABLE api_token_grant
    ADD CONSTRAINT ck_api_token_grant_resource CHECK (
        (resource_kind = '' AND resource_pattern = '')
        OR (resource_kind = ANY (ARRAY['QUEUE'::text, 'ADDRESS'::text]) AND resource_pattern <> ''));
--rollback ALTER TABLE api_token_grant DROP CONSTRAINT ck_api_token_grant_resource;
--rollback ALTER TABLE api_token_grant DROP CONSTRAINT pk_api_token_grant;
--rollback ALTER TABLE api_token_grant ADD CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id);
--rollback ALTER TABLE api_token_grant DROP COLUMN resource_pattern;
--rollback ALTER TABLE api_token_grant DROP COLUMN resource_kind;
