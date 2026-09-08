--liquibase formatted sql

-- Divert-based message capture (ADR-0062). A subscription stops being only a
-- sampling instruction and becomes a mode: SAMPLE keeps ADR-0060's browse poller,
-- CAPTURE installs a tap and drains it. Both write into the same message_index, so
-- a row now has to say which it was — an operator reading a gap has to be able to
-- tell "nothing arrived" from "nothing was observed".
--
-- Column order follows the project convention: 8-byte-aligned types first, then
-- 4-byte, then uuid and boolean last.

--changeset artemis-studio:022-subscription-capture-columns
--comment: capture's per-subscription bounds. Defaults reproduce today's behaviour
--         exactly, so an existing subscription keeps sampling and keeps its shape.
ALTER TABLE message_index_subscription
    ADD COLUMN ring_size       BIGINT  NOT NULL DEFAULT 10000,
    ADD COLUMN max_bytes       BIGINT  NOT NULL DEFAULT 5368709120,
    ADD COLUMN max_rate        INTEGER NOT NULL DEFAULT 500,
    ADD COLUMN body_cap_bytes  INTEGER NOT NULL DEFAULT 262144,
    ADD COLUMN mode            TEXT    NOT NULL DEFAULT 'SAMPLE',
    ADD COLUMN filter_string   TEXT,
    ADD CONSTRAINT ck_message_index_subscription_mode CHECK (mode IN ('SAMPLE', 'CAPTURE')),
    ADD CONSTRAINT ck_message_index_subscription_ring CHECK (ring_size BETWEEN 100 AND 10000000),
    ADD CONSTRAINT ck_message_index_subscription_rate CHECK (max_rate BETWEEN 1 AND 1000000),
    ADD CONSTRAINT ck_message_index_subscription_body_cap CHECK (body_cap_bytes BETWEEN 1024 AND 16777216),
    ADD CONSTRAINT ck_message_index_subscription_max_bytes CHECK (max_bytes > 0);
--rollback ALTER TABLE message_index_subscription
--rollback     DROP CONSTRAINT ck_message_index_subscription_mode,
--rollback     DROP CONSTRAINT ck_message_index_subscription_ring,
--rollback     DROP CONSTRAINT ck_message_index_subscription_rate,
--rollback     DROP CONSTRAINT ck_message_index_subscription_body_cap,
--rollback     DROP CONSTRAINT ck_message_index_subscription_max_bytes,
--rollback     DROP COLUMN ring_size, DROP COLUMN max_bytes, DROP COLUMN max_rate,
--rollback     DROP COLUMN body_cap_bytes, DROP COLUMN mode, DROP COLUMN filter_string;

--changeset artemis-studio:022-message-capture-node
--comment: capture state is per node, not per subscription. A tap is a node-local
--         object, broker RBAC is a node-local fact, and a failover produces a live
--         node that was uncaptured between promotion and the next reconcile pass —
--         a window that has to be recorded rather than averaged away.
CREATE TABLE message_capture_node (
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    captured_from    TIMESTAMPTZ,
    dropped_estimate BIGINT NOT NULL DEFAULT 0,
    held_bytes       BIGINT NOT NULL DEFAULT 0,
    capture_state    TEXT NOT NULL DEFAULT 'PENDING',
    capture_detail   TEXT,
    subscription_id  UUID NOT NULL,
    node_id          UUID NOT NULL,
    CONSTRAINT pk_message_capture_node PRIMARY KEY (subscription_id, node_id),
    CONSTRAINT fk_message_capture_node_subscription FOREIGN KEY (subscription_id)
        REFERENCES message_index_subscription (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_capture_node_node FOREIGN KEY (node_id)
        REFERENCES broker_node (id) ON DELETE CASCADE,
    CONSTRAINT ck_message_capture_node_state
        CHECK (capture_state IN ('PENDING', 'ACTIVE', 'DEGRADED', 'FAILED'))
);

ALTER TABLE message_capture_node SET (
    fillfactor = 80,
    autovacuum_vacuum_scale_factor = 0.05
);
--rollback DROP TABLE message_capture_node;

--changeset artemis-studio:022-message-index-origin
--comment: `origin` backfills to SAMPLED, which is what every existing row is. The
--         source address and message id come from Studio's own capture-queue mapping
--         and from _AMQ_ORIG_MESSAGE_ID respectively; both are null for a sampled
--         row, and source_message_id is null on a captured one when the broker did
--         not copy the header — which the console states rather than hides.
ALTER TABLE message_index
    ADD COLUMN source_message_id BIGINT,
    ADD COLUMN origin            TEXT NOT NULL DEFAULT 'SAMPLED',
    ADD COLUMN orig_address      TEXT,
    ADD COLUMN body_truncated    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE message_index
    ADD CONSTRAINT ck_message_index_origin CHECK (origin IN ('SAMPLED', 'CAPTURED'));
--rollback ALTER TABLE message_index DROP CONSTRAINT ck_message_index_origin;
--rollback ALTER TABLE message_index DROP COLUMN source_message_id, DROP COLUMN origin,
--rollback     DROP COLUMN orig_address, DROP COLUMN body_truncated;
