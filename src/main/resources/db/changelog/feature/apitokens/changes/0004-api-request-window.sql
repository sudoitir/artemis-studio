--liquibase formatted sql

-- The per-minute request count of each API token and each token owner (ADR-0136), shared by the
-- replicas (ADR-0148) so the limit is exact across them. A request is one INSERT ... ON CONFLICT
-- DO UPDATE, which also removes the key's older minutes; a key that stops calling leaves one row,
-- which the data lifecycle purges (ADR-0134). Counts are lost, harmlessly, on a crash, so the table
-- is UNLOGGED. Updated on every request, so it keeps room for HOT updates and is vacuumed early.
-- Column order follows the padding rule (8-byte first, then 4-byte, uuid last). Never edit this
-- file once released; add a new changeset beside it.

--changeset artemis-studio:feature-apitokens-0004-api-request-window
CREATE UNLOGGED TABLE api_request_window (
    minute bigint NOT NULL,
    count integer NOT NULL,
    key uuid NOT NULL,
    CONSTRAINT pk_api_request_window PRIMARY KEY (key, minute)
) WITH (fillfactor = 50, autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 100);
--rollback DROP TABLE IF EXISTS api_request_window;
