--liquibase formatted sql

-- Phase 5 gaps in the Phase 1 request-reply schema (007-request-reply.sql,
-- released — never edited). rr_event had no primary key, so no JPA entity
-- could map it; rr_expectation had no reply-address field for the
-- shared-reply-queue pattern; rr_flow had no keys to dedupe across sample
-- ticks, sweep for deadlines, or join a reply back to its request.
--
-- Note: the column-alignment convention (docs/adr/0008, restated at the top
-- of db.changelog-master.xml) governs CREATE TABLE. ALTER TABLE ADD COLUMN
-- always appends physically regardless of alignment — that is a Postgres
-- constraint, not a lapse of the convention, here or in any other changeset
-- that adds columns to a released table.

--changeset artemis-studio:011-rr-event-pk
--comment: append-only child of rr_flow — same PK shape as broker_event (ADR-0028): insertion order is the identity.
ALTER TABLE rr_event ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE rr_event ADD CONSTRAINT pk_rr_event PRIMARY KEY (seq);
--rollback ALTER TABLE rr_event DROP CONSTRAINT pk_rr_event;
--rollback ALTER TABLE rr_event DROP COLUMN seq;

--changeset artemis-studio:011-rr-expectation-reply
--comment: reply_address names the queue to browse for the shared-reply-queue pattern; correlation_property overrides the JMS-correlation-id assumption; capture_payload gates body capture per expectation.
ALTER TABLE rr_expectation
    ADD COLUMN reply_address        TEXT,
    ADD COLUMN correlation_property TEXT,
    ADD COLUMN capture_payload      BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE rr_expectation DROP COLUMN capture_payload;
--rollback ALTER TABLE rr_expectation DROP COLUMN correlation_property;
--rollback ALTER TABLE rr_expectation DROP COLUMN reply_address;

--changeset artemis-studio:011-rr-flow-keys
--comment: dedupe/sweep/join keys the sampler and correlator need; node_id records which node the request was observed on.
ALTER TABLE rr_flow
    ADD COLUMN observed_at         TIMESTAMPTZ,
    ADD COLUMN request_message_id  TEXT,
    ADD COLUMN reply_message_id    TEXT,
    ADD COLUMN responder_consumer  TEXT,
    ADD COLUMN node_id             UUID,
    ADD CONSTRAINT fk_rr_flow_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE SET NULL;
--rollback ALTER TABLE rr_flow DROP CONSTRAINT fk_rr_flow_node;
--rollback ALTER TABLE rr_flow DROP COLUMN node_id;
--rollback ALTER TABLE rr_flow DROP COLUMN responder_consumer;
--rollback ALTER TABLE rr_flow DROP COLUMN reply_message_id;
--rollback ALTER TABLE rr_flow DROP COLUMN request_message_id;
--rollback ALTER TABLE rr_flow DROP COLUMN observed_at;

--changeset artemis-studio:011-rr-flow-dedup-index
--comment: the same request head message seen on repeated sample ticks is one flow, not a new row each time.
CREATE UNIQUE INDEX uq_rr_flow_request
    ON rr_flow (cluster_id, request_address, request_message_id)
    WHERE request_message_id IS NOT NULL;
--rollback DROP INDEX uq_rr_flow_request;

--changeset artemis-studio:011-rr-flow-deadline-index
--comment: the sweep's only query.
CREATE INDEX ix_rr_flow_deadline
    ON rr_flow (deadline_at) WHERE state = 'AWAITING_REPLY';
--rollback DROP INDEX ix_rr_flow_deadline;

--changeset artemis-studio:011-rr-flow-open-reply-index
--comment: the reply-join query — matches on the temp-queue destination or either JMS correlation convention.
CREATE INDEX ix_rr_flow_open_reply
    ON rr_flow (cluster_id, reply_destination, correlation_id) WHERE state = 'AWAITING_REPLY';
--rollback DROP INDEX ix_rr_flow_open_reply;

--changeset artemis-studio:011-rr-flow-request-address-nullable
--comment: a reply observed with no matching request (ORPHANED_REPLY) has no known request address — nullable rather than a fabricated placeholder.
ALTER TABLE rr_flow ALTER COLUMN request_address DROP NOT NULL;
--rollback ALTER TABLE rr_flow ALTER COLUMN request_address SET NOT NULL;
