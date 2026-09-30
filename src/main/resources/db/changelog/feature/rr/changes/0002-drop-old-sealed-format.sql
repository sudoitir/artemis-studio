--liquibase formatted sql

-- rr_event.detail kept a bare AES-GCM ciphertext in sealed and its nonce beside it. The sealed original is now one
-- envelope-encrypted blob (ADR-0132 D2). Existing originals cannot be converted (no data migration before the
-- stable release), so they are dropped and the events keep their other fields.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-rr-0002-drop-old-sealed-format
UPDATE rr_event SET detail = detail - 'sealed' - 'nonce' WHERE jsonb_exists(detail, 'nonce');
--rollback empty
