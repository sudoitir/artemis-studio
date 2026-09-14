--liquibase formatted sql

-- The capture queue's message bound is lowered to 1,000,000 (ADR-0079). The queue is now bounded in bytes on the
-- broker as well, which makes a larger count meaningless, and a ring of ten million large messages was never
-- really bounded. Existing subscriptions above the new ceiling are clamped to it rather than left unloadable.
-- Never edit this file once released.

--changeset artemis-studio:feature-sql-0003-capture-ring-bound splitStatements:true
UPDATE message_index_subscription SET ring_size = 1000000 WHERE ring_size > 1000000;
ALTER TABLE message_index_subscription DROP CONSTRAINT ck_message_index_subscription_ring;
ALTER TABLE message_index_subscription
    ADD CONSTRAINT ck_message_index_subscription_ring CHECK (ring_size >= 100 AND ring_size <= 1000000);
