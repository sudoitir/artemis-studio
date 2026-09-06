--liquibase formatted sql

-- An expectation's reply destination becomes a set of patterns rather than a
-- single address. Both clusters Studio has been pointed at answer on a shared
-- reply queue *per responder instance* — per broker node in one, per client host
-- in the other — so the single reply_address added in 011 could never hold the
-- answer, and the operator correctly left it empty, which is the one value that
-- makes a shared-queue flow impossible to join.
--
-- A per-client-host reply queue cannot be enumerated in advance, so entries are
-- globs (`*` matches any run of characters, anchored at both ends) resolved
-- against the addresses already in queue_snapshot. An empty array is not "unset":
-- it is the explicit statement that replies arrive on a temporary queue named by
-- the request, which is why the column is NOT NULL DEFAULT '{}' rather than
-- nullable — two spellings of one state would force every reader to handle both.
--
-- TEXT[] is a varlena, so it sits with the other 4-byte-aligned columns, before
-- the uuid and boolean tail. Postgres is the only supported database (ADR-0011).

--changeset artemis-studio:017-rr-reply-addresses
--comment: reply_address (single, added in 011) becomes reply_addresses TEXT[], a set of literal-or-glob patterns; an empty array means the temporary-reply-queue pattern.
ALTER TABLE rr_expectation
    ADD COLUMN reply_addresses TEXT[] NOT NULL DEFAULT '{}';

UPDATE rr_expectation
   SET reply_addresses = ARRAY[reply_address]
 WHERE reply_address IS NOT NULL
   AND reply_address <> '';

ALTER TABLE rr_expectation
    DROP COLUMN reply_address;
--rollback ALTER TABLE rr_expectation ADD COLUMN reply_address TEXT;
--rollback UPDATE rr_expectation SET reply_address = reply_addresses[1] WHERE cardinality(reply_addresses) > 0;
--rollback ALTER TABLE rr_expectation DROP COLUMN reply_addresses;
