--liquibase formatted sql

-- Token rotation, the lifetime cap, MCP tool allow-lists and hourly usage (ADR-0136).
-- Every token now has an expiry: tokens minted without one get created_at + 90 days, the
-- default cap. The rotation columns hold the previous secret, kept after its overlap ends so
-- a late use is recognised and audited. The new columns are appended; rewriting api_token to
-- reorder them is not worth it. Never edit this file once released.

--changeset artemis-studio:feature-apitokens-0002-rotation-lifetime-usage splitStatements:true
UPDATE api_token SET expires_at = created_at + interval '90 days' WHERE expires_at IS NULL;

ALTER TABLE api_token ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE api_token ADD COLUMN previous_valid_until timestamp with time zone;
ALTER TABLE api_token ADD COLUMN previous_prefix text;
ALTER TABLE api_token ADD COLUMN previous_token_hash bytea;
ALTER TABLE api_token ADD COLUMN mcp_tools text[] DEFAULT '{}'::text[] NOT NULL;

CREATE UNIQUE INDEX uq_api_token_previous_prefix ON api_token USING btree (previous_prefix)
    WHERE (previous_prefix IS NOT NULL);

CREATE TABLE api_token_usage (
    hour timestamp with time zone NOT NULL,
    requests bigint DEFAULT 0 NOT NULL,
    denied bigint DEFAULT 0 NOT NULL,
    limited bigint DEFAULT 0 NOT NULL,
    errors bigint DEFAULT 0 NOT NULL,
    token_id uuid NOT NULL
);

ALTER TABLE ONLY api_token_usage
    ADD CONSTRAINT pk_api_token_usage PRIMARY KEY (token_id, hour);

ALTER TABLE ONLY api_token_usage
    ADD CONSTRAINT fk_api_token_usage_token FOREIGN KEY (token_id) REFERENCES api_token(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS api_token_usage;
--rollback DROP INDEX IF EXISTS uq_api_token_previous_prefix;
--rollback ALTER TABLE api_token DROP COLUMN IF EXISTS mcp_tools;
--rollback ALTER TABLE api_token DROP COLUMN IF EXISTS previous_token_hash;
--rollback ALTER TABLE api_token DROP COLUMN IF EXISTS previous_prefix;
--rollback ALTER TABLE api_token DROP COLUMN IF EXISTS previous_valid_until;
--rollback ALTER TABLE api_token ALTER COLUMN expires_at DROP NOT NULL;
