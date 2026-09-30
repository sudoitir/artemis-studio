--liquibase formatted sql

-- Short-lived references to a console query (ADR-0064), shared by the replicas (ADR-0152): the POST
-- that issues one and the stream GET that redeems it may reach different replicas. A ticket is
-- worthless a minute after it is issued and is lost, harmlessly, on a crash, so the table is
-- UNLOGGED. It is redeemed once, by a DELETE ... RETURNING, and expired rows are purged by the data
-- lifecycle (ADR-0134). Column order follows the padding rule (8-byte first, then text, uuid and
-- boolean last). Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-sql-0006-sql-query-ticket
CREATE UNLOGGED TABLE sql_query_ticket (
    expires_at timestamp with time zone NOT NULL,
    sql text NOT NULL,
    owner text NOT NULL,
    id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    tail boolean NOT NULL,
    CONSTRAINT pk_sql_query_ticket PRIMARY KEY (id)
) WITH (autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 50);
CREATE INDEX ix_sql_query_ticket_expires_at ON sql_query_ticket (expires_at);
--rollback DROP TABLE IF EXISTS sql_query_ticket;
