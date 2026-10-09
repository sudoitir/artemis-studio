--liquibase formatted sql

-- A request no longer carries the requester's reason: it needed a field on every action that can be held, and
-- most actions have none. The approver's reason for a rejection stays (decision_reason).

--changeset artemis-studio:kernel-approval-0002-guard-without-reason splitStatements:false
-- The guard names every column of what was requested, so it stops naming the one that is going.
CREATE OR REPLACE FUNCTION held_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    moved boolean := NEW.state IS DISTINCT FROM OLD.state;
    ending boolean := moved AND NEW.state IN ('REJECTED', 'CANCELLED', 'EXPIRED', 'SUCCEEDED', 'FAILED', 'REFUSED',
        'OUTCOME_UNKNOWN');
BEGIN
    IF (NEW.id, NEW.type, NEW.type_version, NEW.mode, NEW.auth_kind, NEW.provider_id, NEW.requester_id,
            NEW.token_id, NEW.token_name, NEW.requester_username, NEW.summary, NEW.approver_hint, NEW.traits,
            NEW.params, NEW.display, NEW.effect, NEW.policy, NEW.params_hash, NEW.cluster_id, NEW.environment_id,
            NEW.requested_at, NEW.expires_at, NEW.request_audit_id)
        IS DISTINCT FROM
        (OLD.id, OLD.type, OLD.type_version, OLD.mode, OLD.auth_kind, OLD.provider_id, OLD.requester_id,
            OLD.token_id, OLD.token_name, OLD.requester_username, OLD.summary, OLD.approver_hint, OLD.traits,
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

--changeset artemis-studio:kernel-approval-0002-drop-requester-reason
ALTER TABLE held_operation DROP COLUMN reason;
