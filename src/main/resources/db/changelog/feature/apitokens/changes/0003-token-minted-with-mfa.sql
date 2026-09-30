--liquibase formatted sql

-- Whether a token was minted from a session that had verified a second factor (ADR-0142, D7). A token
-- minted without one stops authenticating once its owner's roles require one. Tokens that exist
-- when this is added count as minted without, so an administrator's older tokens are reminted after
-- they verify a factor.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-apitokens-0003-token-minted-with-mfa
ALTER TABLE api_token ADD COLUMN minted_with_mfa boolean DEFAULT false NOT NULL;
--rollback ALTER TABLE api_token DROP COLUMN minted_with_mfa;
