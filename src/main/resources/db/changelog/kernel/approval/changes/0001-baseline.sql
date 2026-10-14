--liquibase formatted sql

-- The kernel/approval module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:kernel-approval-0001-baseline splitStatements:true
CREATE TABLE held_operation (
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    decided_at timestamp with time zone,
    run_deadline timestamp with time zone,
    claimed_at timestamp with time zone,
    finished_at timestamp with time zone,
    request_audit_id bigint NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    type_version integer NOT NULL,
    type text NOT NULL,
    state text DEFAULT 'HELD'::text NOT NULL,
    mode text NOT NULL,
    auth_kind text NOT NULL,
    provider_id text NOT NULL,
    requester_username text NOT NULL,
    token_name text,
    approver_username text,
    summary text NOT NULL,
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
    id uuid DEFAULT uuidv7() NOT NULL,
    requester_id uuid NOT NULL,
    token_id uuid,
    approver_id uuid,
    claimed_by uuid,
    cluster_id uuid,
    environment_id uuid,
    CONSTRAINT ck_held_operation_approved CHECK (((state <> 'APPROVED'::text) OR (run_deadline IS NOT NULL))),
    CONSTRAINT ck_held_operation_approver_hint CHECK ((char_length(approver_hint) <= 200)),
    CONSTRAINT ck_held_operation_auth_kind CHECK ((auth_kind = ANY (ARRAY['SESSION'::text, 'TOKEN'::text, 'AGENT'::text]))),
    CONSTRAINT ck_held_operation_claim CHECK (((state <> 'EXECUTING'::text) OR ((claimed_at IS NOT NULL) AND (claimed_by IS NOT NULL)))),
    CONSTRAINT ck_held_operation_decided CHECK (((state = ANY (ARRAY['HELD'::text, 'CANCELLED'::text, 'EXPIRED'::text])) OR ((approver_id IS NOT NULL) AND (approver_username IS NOT NULL) AND (sealed_decision IS NOT NULL) AND (decided_at IS NOT NULL)))),
    CONSTRAINT ck_held_operation_decision_reason CHECK ((char_length(decision_reason) <= 500)),
    CONSTRAINT ck_held_operation_expiry CHECK ((expires_at > requested_at)),
    CONSTRAINT ck_held_operation_finished CHECK (((state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text])) = (finished_at IS NULL))),
    CONSTRAINT ck_held_operation_mode CHECK ((mode = ANY (ARRAY['ON_APPROVAL'::text, 'BY_REQUESTER'::text]))),
    CONSTRAINT ck_held_operation_not_self CHECK ((approver_id <> requester_id)),
    CONSTRAINT ck_held_operation_outcome_detail CHECK ((char_length(outcome_detail) <= 2000)),
    CONSTRAINT ck_held_operation_params_hash CHECK ((octet_length(params_hash) = 32)),
    CONSTRAINT ck_held_operation_payload CHECK (((state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text])) = (sealed_payload IS NOT NULL))),
    CONSTRAINT ck_held_operation_session_token CHECK ((((auth_kind <> 'SESSION'::text) OR ((token_id IS NULL) AND (token_name IS NULL))) AND ((auth_kind <> 'TOKEN'::text) OR (token_id IS NOT NULL)) AND ((token_id IS NULL) = (token_name IS NULL)))),
    CONSTRAINT ck_held_operation_state CHECK ((state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text, 'REJECTED'::text, 'CANCELLED'::text, 'EXPIRED'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'REFUSED'::text, 'OUTCOME_UNKNOWN'::text]))),
    CONSTRAINT ck_held_operation_summary CHECK (((char_length(summary) >= 1) AND (char_length(summary) <= 500)))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE held_operation_event (
    at timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint NOT NULL,
    txid xid8 DEFAULT pg_current_xact_id() NOT NULL,
    kind text NOT NULL,
    actor_username text,
    detail text,
    held_id uuid NOT NULL,
    actor_id uuid,
    CONSTRAINT ck_held_operation_event_detail CHECK ((char_length(detail) <= 2000)),
    CONSTRAINT ck_held_operation_event_kind CHECK ((kind = ANY (ARRAY['REQUESTED'::text, 'APPROVED'::text, 'REJECTED'::text, 'VOTE_REFUSED'::text, 'CANCELLED'::text, 'EXPIRED'::text, 'EXECUTING'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'REFUSED'::text, 'OUTCOME_UNKNOWN'::text])))
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000', autovacuum_analyze_scale_factor='0.02');

ALTER TABLE held_operation_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME held_operation_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY held_operation
    ADD CONSTRAINT pk_held_operation PRIMARY KEY (id);

ALTER TABLE ONLY held_operation_event
    ADD CONSTRAINT pk_held_operation_event PRIMARY KEY (seq);

CREATE INDEX ix_held_operation_approved_deadline ON held_operation USING btree (run_deadline) WHERE (state = 'APPROVED'::text);

CREATE INDEX ix_held_operation_approver_id ON held_operation USING btree (approver_id);

CREATE INDEX ix_held_operation_event_actor_id ON held_operation_event USING btree (actor_id);

CREATE INDEX ix_held_operation_event_cursor ON held_operation_event USING btree (txid, seq);

CREATE INDEX ix_held_operation_event_held ON held_operation_event USING btree (held_id, seq);

CREATE INDEX ix_held_operation_executing_claim ON held_operation USING btree (claimed_at) WHERE (state = 'EXECUTING'::text);

CREATE INDEX ix_held_operation_finished ON held_operation USING btree (finished_at) WHERE (finished_at IS NOT NULL);

CREATE INDEX ix_held_operation_held_expiry ON held_operation USING btree (expires_at) WHERE (state = 'HELD'::text);

CREATE INDEX ix_held_operation_open_cluster ON held_operation USING btree (cluster_id) WHERE (state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text]));

CREATE INDEX ix_held_operation_open_provider ON held_operation USING btree (provider_id) WHERE (state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text]));

CREATE INDEX ix_held_operation_requester ON held_operation USING btree (requester_id, id DESC);

CREATE UNIQUE INDEX ux_held_operation_open_duplicate ON held_operation USING btree (requester_id, params_hash) WHERE (state = ANY (ARRAY['HELD'::text, 'APPROVED'::text, 'EXECUTING'::text]));

ALTER TABLE ONLY held_operation
    ADD CONSTRAINT fk_held_operation_approver FOREIGN KEY (approver_id) REFERENCES app_user(id);

ALTER TABLE ONLY held_operation_event
    ADD CONSTRAINT fk_held_operation_event_actor FOREIGN KEY (actor_id) REFERENCES app_user(id);

ALTER TABLE ONLY held_operation_event
    ADD CONSTRAINT fk_held_operation_event_held FOREIGN KEY (held_id) REFERENCES held_operation(id) ON DELETE CASCADE;

ALTER TABLE ONLY held_operation
    ADD CONSTRAINT fk_held_operation_requester FOREIGN KEY (requester_id) REFERENCES app_user(id);
--rollback DROP TABLE IF EXISTS held_operation_event CASCADE;
--rollback DROP TABLE IF EXISTS held_operation CASCADE;

--changeset artemis-studio:kernel-approval-0001-baseline-held-operation-delete-guard splitStatements:false
CREATE FUNCTION held_operation_delete_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.finished_at IS NULL OR OLD.finished_at > now() - interval '1 day' THEN
        RAISE EXCEPTION 'held operation %: only an operation that ended a day ago may be deleted', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END
$$;
--rollback DROP FUNCTION IF EXISTS held_operation_delete_guard CASCADE;

--changeset artemis-studio:kernel-approval-0001-baseline-held-operation-event-guard splitStatements:false
CREATE FUNCTION held_operation_event_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
--rollback DROP FUNCTION IF EXISTS held_operation_event_guard CASCADE;

--changeset artemis-studio:kernel-approval-0001-baseline-held-operation-guard splitStatements:false
CREATE FUNCTION held_operation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
--rollback DROP FUNCTION IF EXISTS held_operation_guard CASCADE;

--changeset artemis-studio:kernel-approval-0001-baseline-held-operation-kek splitStatements:false
CREATE FUNCTION held_operation_kek(blob bytea) RETURNS bigint
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE WHEN octet_length(blob) >= 93 AND get_byte(blob, 0) = 1
        THEN (get_byte(blob, 1)::bigint << 24) | (get_byte(blob, 2) << 16) | (get_byte(blob, 3) << 8)
            | get_byte(blob, 4) END
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;
--rollback DROP FUNCTION IF EXISTS held_operation_kek CASCADE;

--changeset artemis-studio:kernel-approval-0001-baseline-triggers splitStatements:true
CREATE TRIGGER tg_held_operation_delete_guard BEFORE DELETE ON held_operation FOR EACH ROW EXECUTE FUNCTION held_operation_delete_guard();

CREATE TRIGGER tg_held_operation_event_guard BEFORE INSERT OR DELETE OR UPDATE ON held_operation_event FOR EACH ROW EXECUTE FUNCTION held_operation_event_guard();

CREATE TRIGGER tg_held_operation_guard BEFORE UPDATE ON held_operation FOR EACH ROW EXECUTE FUNCTION held_operation_guard();
--rollback DROP TRIGGER IF EXISTS tg_held_operation_delete_guard ON held_operation;
--rollback DROP TRIGGER IF EXISTS tg_held_operation_event_guard ON held_operation_event;
--rollback DROP TRIGGER IF EXISTS tg_held_operation_guard ON held_operation;
