--liquibase formatted sql

-- Operations an approval provider held, and their timeline (ADR-0180). Studio owns them, whichever
-- provider holds them. The plugins share this database role (ADR-0103), so the rows are bound by seals
-- whose key lives outside the database; the CHECKs and triggers here keep honest code honest and make a
-- direct edit loud. Never edit this file once released; add a new file beside it.

--changeset artemis-studio:kernel-approval-0001-held-operation
CREATE TABLE held_operation (
    requested_at timestamp with time zone NOT NULL DEFAULT now(),
    expires_at timestamp with time zone NOT NULL,
    decided_at timestamp with time zone,
    run_deadline timestamp with time zone,
    claimed_at timestamp with time zone,
    finished_at timestamp with time zone,
    request_audit_id bigint NOT NULL,
    version integer NOT NULL DEFAULT 0,
    type_version integer NOT NULL,
    type text NOT NULL,
    state text NOT NULL DEFAULT 'HELD',
    mode text NOT NULL,
    auth_kind text NOT NULL,
    provider_id text NOT NULL,
    requester_username text NOT NULL,
    approver_username text,
    summary text NOT NULL,
    reason text,
    approver_hint text,
    decision_reason text,
    outcome_detail text,
    traits text[] NOT NULL,
    params jsonb NOT NULL,
    display jsonb NOT NULL,
    effect jsonb NOT NULL,
    policy jsonb NOT NULL,
    params_hash bytea NOT NULL,
    sealed_payload bytea,
    sealed_decision bytea,
    id uuid NOT NULL DEFAULT uuidv7(),
    requester_id uuid NOT NULL,
    token_id uuid,
    approver_id uuid,
    claimed_by uuid,
    cluster_id uuid,
    environment_id uuid,
    CONSTRAINT pk_held_operation PRIMARY KEY (id),
    CONSTRAINT fk_held_operation_requester FOREIGN KEY (requester_id) REFERENCES app_user(id),
    CONSTRAINT fk_held_operation_approver FOREIGN KEY (approver_id) REFERENCES app_user(id),
    CONSTRAINT ck_held_operation_state CHECK (state IN ('HELD', 'APPROVED', 'EXECUTING', 'REJECTED', 'CANCELLED',
        'EXPIRED', 'SUCCEEDED', 'FAILED', 'REFUSED', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_held_operation_mode CHECK (mode IN ('ON_APPROVAL', 'BY_REQUESTER')),
    CONSTRAINT ck_held_operation_auth_kind CHECK (auth_kind IN ('SESSION', 'TOKEN', 'AGENT')),
    CONSTRAINT ck_held_operation_session_token CHECK (
        (auth_kind <> 'SESSION' OR token_id IS NULL) AND (auth_kind <> 'TOKEN' OR token_id IS NOT NULL)),
    CONSTRAINT ck_held_operation_not_self CHECK (approver_id <> requester_id),
    CONSTRAINT ck_held_operation_decided CHECK (
        state IN ('HELD', 'CANCELLED', 'EXPIRED')
        OR (approver_id IS NOT NULL AND approver_username IS NOT NULL AND sealed_decision IS NOT NULL
            AND decided_at IS NOT NULL)),
    CONSTRAINT ck_held_operation_approved CHECK (state <> 'APPROVED' OR run_deadline IS NOT NULL),
    CONSTRAINT ck_held_operation_payload CHECK (
        (state IN ('HELD', 'APPROVED', 'EXECUTING')) = (sealed_payload IS NOT NULL)),
    CONSTRAINT ck_held_operation_finished CHECK (
        (state IN ('HELD', 'APPROVED', 'EXECUTING')) = (finished_at IS NULL)),
    CONSTRAINT ck_held_operation_claim CHECK (state <> 'EXECUTING' OR (claimed_at IS NOT NULL AND claimed_by IS NOT NULL)),
    CONSTRAINT ck_held_operation_expiry CHECK (expires_at > requested_at),
    CONSTRAINT ck_held_operation_params_hash CHECK (octet_length(params_hash) = 32),
    CONSTRAINT ck_held_operation_summary CHECK (char_length(summary) BETWEEN 1 AND 500),
    CONSTRAINT ck_held_operation_reason CHECK (char_length(reason) <= 500),
    CONSTRAINT ck_held_operation_decision_reason CHECK (char_length(decision_reason) <= 500),
    CONSTRAINT ck_held_operation_approver_hint CHECK (char_length(approver_hint) <= 200),
    CONSTRAINT ck_held_operation_outcome_detail CHECK (char_length(outcome_detail) <= 2000)
) WITH (fillfactor = 80);

-- What the jobs scan, one partial index per state.
CREATE INDEX ix_held_operation_held_expiry ON held_operation (expires_at) WHERE state = 'HELD';
CREATE INDEX ix_held_operation_approved_deadline ON held_operation (run_deadline) WHERE state = 'APPROVED';
CREATE INDEX ix_held_operation_executing_claim ON held_operation (claimed_at) WHERE state = 'EXECUTING';
CREATE INDEX ix_held_operation_open_provider ON held_operation (provider_id)
    WHERE state IN ('HELD', 'APPROVED', 'EXECUTING');
-- What the screens and the retention read.
CREATE INDEX ix_held_operation_requester ON held_operation (requester_id, id DESC);
CREATE INDEX ix_held_operation_open_cluster ON held_operation (cluster_id)
    WHERE state IN ('HELD', 'APPROVED', 'EXECUTING');
CREATE INDEX ix_held_operation_finished ON held_operation (finished_at) WHERE finished_at IS NOT NULL;
-- The same request, asked again while the first is open, is the first.
CREATE UNIQUE INDEX ux_held_operation_open_duplicate ON held_operation (requester_id, params_hash)
    WHERE state IN ('HELD', 'APPROVED', 'EXECUTING');

CREATE TABLE held_operation_event (
    at timestamp with time zone NOT NULL DEFAULT now(),
    seq bigint GENERATED ALWAYS AS IDENTITY,
    txid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    kind text NOT NULL,
    actor_username text,
    detail text,
    held_id uuid NOT NULL,
    actor_id uuid,
    CONSTRAINT pk_held_operation_event PRIMARY KEY (seq),
    CONSTRAINT fk_held_operation_event_held FOREIGN KEY (held_id) REFERENCES held_operation(id) ON DELETE CASCADE,
    CONSTRAINT fk_held_operation_event_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT ck_held_operation_event_kind CHECK (kind IN ('REQUESTED', 'APPROVED', 'REJECTED', 'VOTE_REFUSED',
        'CANCELLED', 'EXPIRED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'REFUSED', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_held_operation_event_detail CHECK (char_length(detail) <= 2000)
);

CREATE INDEX ix_held_operation_event_held ON held_operation_event (held_id, seq);
CREATE INDEX ix_held_operation_event_cursor ON held_operation_event (txid, seq);

--changeset artemis-studio:kernel-approval-0001-held-operation-kek splitStatements:false
-- The key version of a SecretVault blob (bytes 1..4, big-endian), as SealedStore.versionOf reads it; null
-- for anything that is not a well-formed blob (shorter than SecretVault.MIN_BLOB_BYTES, 93, or of another
-- format).
CREATE FUNCTION held_operation_kek(blob bytea) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN octet_length(blob) >= 93 AND get_byte(blob, 0) = 1
        THEN (get_byte(blob, 1)::bigint << 24) | (get_byte(blob, 2) << 16) | (get_byte(blob, 3) << 8)
            | get_byte(blob, 4) END
$$;

--changeset artemis-studio:kernel-approval-0001-held-operation-guard splitStatements:false
-- Moves a held operation only along its life (ADR-0180), and never changes what was asked: the request
-- columns are fixed, each decision and claim column is written once by the move that sets it, the
-- version counts the moves, and a seal changes only to be wiped at the end or, while the state stands
-- still, re-wrapped by a key rotation into another well-formed blob. Which blob is genuine is the seal's
-- own business: its additional data names the row, so only the vault can make one that opens here. Every
-- condition is null-safe: a null in a plpgsql IF reads as false, which here would let a change through.
CREATE FUNCTION held_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    moved boolean := NEW.state IS DISTINCT FROM OLD.state;
    ending boolean := moved AND NEW.state IN ('REJECTED', 'CANCELLED', 'EXPIRED', 'SUCCEEDED', 'FAILED', 'REFUSED',
        'OUTCOME_UNKNOWN');
BEGIN
    IF (NEW.id, NEW.type, NEW.type_version, NEW.mode, NEW.auth_kind, NEW.provider_id, NEW.requester_id,
            NEW.token_id, NEW.requester_username, NEW.summary, NEW.reason, NEW.approver_hint, NEW.traits,
            NEW.params, NEW.display, NEW.effect, NEW.policy, NEW.params_hash, NEW.cluster_id, NEW.environment_id,
            NEW.requested_at, NEW.expires_at, NEW.request_audit_id)
        IS DISTINCT FROM
        (OLD.id, OLD.type, OLD.type_version, OLD.mode, OLD.auth_kind, OLD.provider_id, OLD.requester_id,
            OLD.token_id, OLD.requester_username, OLD.summary, OLD.reason, OLD.approver_hint, OLD.traits,
            OLD.params, OLD.display, OLD.effect, OLD.policy, OLD.params_hash, OLD.cluster_id, OLD.environment_id,
            OLD.requested_at, OLD.expires_at, OLD.request_audit_id) THEN
        RAISE EXCEPTION 'held operation %: what was requested never changes', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF moved AND NOT (
            (OLD.state = 'HELD' AND NEW.state IN ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'))
            OR (OLD.state = 'APPROVED' AND NEW.state IN ('EXECUTING', 'CANCELLED', 'EXPIRED'))
            OR (OLD.state = 'EXECUTING' AND NEW.state IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'OUTCOME_UNKNOWN'))) THEN
        RAISE EXCEPTION 'held operation %: % never becomes %', OLD.id, OLD.state, NEW.state
            USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.approver_id, NEW.approver_username, NEW.decided_at, NEW.decision_reason, NEW.run_deadline)
            IS DISTINCT FROM
            (OLD.approver_id, OLD.approver_username, OLD.decided_at, OLD.decision_reason, OLD.run_deadline)
        AND NOT (OLD.state = 'HELD' AND NEW.state IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'held operation %: only the decision records who decided', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.sealed_decision IS DISTINCT FROM OLD.sealed_decision
        AND NOT (OLD.state = 'HELD' AND NEW.state IN ('APPROVED', 'REJECTED') AND OLD.sealed_decision IS NULL)
        AND NOT (NOT moved AND OLD.sealed_decision IS NOT NULL AND NEW.sealed_decision IS NOT NULL
            AND held_operation_kek(NEW.sealed_decision) IS NOT NULL) THEN
        RAISE EXCEPTION 'held operation %: the decision seal never changes', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.sealed_payload IS DISTINCT FROM OLD.sealed_payload
        AND NOT (ending AND NEW.sealed_payload IS NULL)
        AND NOT (NOT moved AND OLD.sealed_payload IS NOT NULL AND NEW.sealed_payload IS NOT NULL
            AND held_operation_kek(NEW.sealed_payload) IS NOT NULL) THEN
        RAISE EXCEPTION 'held operation %: the request seal is only wiped when it ends', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.claimed_at, NEW.claimed_by) IS DISTINCT FROM (OLD.claimed_at, OLD.claimed_by)
        AND NOT (OLD.state = 'APPROVED' AND NEW.state = 'EXECUTING') THEN
        RAISE EXCEPTION 'held operation %: only the claim records who runs it', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.finished_at, NEW.outcome_detail) IS DISTINCT FROM (OLD.finished_at, OLD.outcome_detail)
        AND NOT ending THEN
        RAISE EXCEPTION 'held operation %: only its end records how it ended', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.version := CASE WHEN moved THEN OLD.version + 1 ELSE OLD.version END;
    RETURN NEW;
END
$$;

--changeset artemis-studio:kernel-approval-0001-held-operation-delete-guard splitStatements:false
-- An open operation is never deleted, and an ended one only once the shortest retention has passed.
CREATE FUNCTION held_operation_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.finished_at IS NULL OR OLD.finished_at > now() - interval '1 day' THEN
        RAISE EXCEPTION 'held operation %: only an operation that ended a day ago may be deleted', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END
$$;

--changeset artemis-studio:kernel-approval-0001-held-operation-event-guard splitStatements:false
-- The timeline is append-only. Its transaction id and time are the database's, never the writer's, so a
-- reader after a cursor (HeldOperations.eventsAfter) can trust the order; an event goes only with its
-- operation.
CREATE FUNCTION held_operation_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.txid := pg_current_xact_id();
        NEW.at := now();
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'held operation events are never changed' USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM held_operation WHERE id = OLD.held_id) THEN
        RAISE EXCEPTION 'held operation events go only with their operation' USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END
$$;

--changeset artemis-studio:kernel-approval-0001-held-operation-triggers
CREATE TRIGGER tg_held_operation_guard BEFORE UPDATE ON held_operation
    FOR EACH ROW EXECUTE FUNCTION held_operation_guard();
CREATE TRIGGER tg_held_operation_delete_guard BEFORE DELETE ON held_operation
    FOR EACH ROW EXECUTE FUNCTION held_operation_delete_guard();
CREATE TRIGGER tg_held_operation_event_guard BEFORE INSERT OR UPDATE OR DELETE ON held_operation_event
    FOR EACH ROW EXECUTE FUNCTION held_operation_event_guard();
