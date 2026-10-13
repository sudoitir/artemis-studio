--liquibase formatted sql

-- Every audit record gets a commit-order position, (pos_txid, pos_seq): the writing transaction's id
-- and a sequence value, both stamped by the database. A reader after a position (AuditFeed.after)
-- sees a record only once every earlier transaction has ended, so it never skips one that commits
-- late; the identity id cannot give that, because ids are handed out in insert order, not commit
-- order. A record that changes (PENDING to its outcome) takes a new position, so a reader that
-- already passed it sees the final state. The guard keeps what a reader saw from being rewritten.
-- Never edit this file once released.

--changeset artemis-studio:kernel-audit-0004-audit-position splitStatements:false
CREATE SEQUENCE audit_event_position_seq;

SELECT setval('audit_event_position_seq', COALESCE((SELECT max(id) FROM audit_event), 0) + 1, false);

ALTER TABLE audit_event ADD COLUMN pos_txid xid8, ADD COLUMN pos_seq bigint;

UPDATE audit_event SET pos_txid = '0'::text::xid8, pos_seq = id;

ALTER TABLE audit_event ALTER COLUMN pos_txid SET NOT NULL, ALTER COLUMN pos_seq SET NOT NULL;

CREATE INDEX ix_audit_event_position ON audit_event USING btree (pos_txid, pos_seq);

COMMENT ON COLUMN audit_event.pos_txid IS 'Commit-order position, with pos_seq: the id of the transaction that last wrote the row. Set by tg_audit_event_position, never by the writer.';

CREATE FUNCTION audit_event_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - 'pos_txid' - 'pos_seq') = (to_jsonb(OLD) - 'pos_txid' - 'pos_seq') THEN
        NEW.pos_txid := OLD.pos_txid;
        NEW.pos_seq := OLD.pos_seq;
        RETURN NEW;
    END IF;
    NEW.pos_txid := pg_current_xact_id();
    NEW.pos_seq := nextval('audit_event_position_seq');
    RETURN NEW;
END
$$;

-- A record says what happened, so only its outcome may be filled in later: outcome, error, affected
-- count and outcome detail, while PENDING. A refusal stands for a count of identical refusals
-- (RefusalAuditRecorder), so its count alone may keep rising. Nothing else is ever changed.
CREATE FUNCTION audit_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'outcome' - 'error' - 'affected_count' - 'outcome_detail' - 'pos_txid' - 'pos_seq')
            <> (to_jsonb(OLD) - 'outcome' - 'error' - 'affected_count' - 'outcome_detail' - 'pos_txid' - 'pos_seq') THEN
        RAISE EXCEPTION 'audit event %: only its outcome may be changed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.outcome = 'PENDING' THEN
        RETURN NEW;
    END IF;
    IF OLD.outcome = 'REFUSED' AND NEW.outcome = OLD.outcome AND NEW.error IS NOT DISTINCT FROM OLD.error THEN
        RETURN NEW;
    END IF;
    IF NEW.outcome = OLD.outcome AND NEW.error IS NOT DISTINCT FROM OLD.error
            AND NEW.affected_count IS NOT DISTINCT FROM OLD.affected_count
            AND NEW.outcome_detail IS NOT DISTINCT FROM OLD.outcome_detail THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'audit event %: its outcome is final', OLD.id USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER tg_audit_event_guard BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_guard();

CREATE TRIGGER tg_audit_event_position BEFORE INSERT OR UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_position();
--rollback DROP TRIGGER IF EXISTS tg_audit_event_position ON audit_event;
--rollback DROP TRIGGER IF EXISTS tg_audit_event_guard ON audit_event;
--rollback DROP FUNCTION IF EXISTS audit_event_position();
--rollback DROP FUNCTION IF EXISTS audit_event_guard();
--rollback DROP INDEX IF EXISTS ix_audit_event_position;
--rollback ALTER TABLE audit_event DROP COLUMN IF EXISTS pos_txid, DROP COLUMN IF EXISTS pos_seq;
--rollback DROP SEQUENCE IF EXISTS audit_event_position_seq;
