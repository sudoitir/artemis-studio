package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.AuthKind;
import io.github.sudoitir.artemisstudio.kernel.gate.CanonicalJson;
import io.github.sudoitir.artemisstudio.kernel.gate.DisplayRow;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.EventCursor;
import io.github.sudoitir.artemisstudio.kernel.gate.ExecutionMode;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldEvent;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.PolicyRef;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL of {@code held_operation} and {@code held_operation_event} (ADR-0180). Every state change is one conditional
 * {@code UPDATE … RETURNING}, so of two writers racing for the same move only one gets the row back; every time check
 * uses the database clock. Callers own the transactions.
 */
@Component
class HeldStore {

    static final String OPEN = "'HELD', 'APPROVED', 'EXECUTING'";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<DisplayRow>> DISPLAY = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, type, type_version, state, mode, auth_kind, provider_id, requester_id, requester_username, token_id,
            approver_id, approver_username, summary, reason, approver_hint, decision_reason, outcome_detail, traits,
            params::text AS params, display::text AS display, effect::text AS effect, policy::text AS policy,
            params_hash, sealed_payload, sealed_decision, cluster_id, environment_id, requested_at, expires_at,
            decided_at, run_deadline, claimed_at, claimed_by, finished_at, request_audit_id, version""";

    private static final String H_COLUMNS = Arrays.stream(COLUMNS.split(","))
            .map(String::strip)
            .map(c -> "h." + c)
            .collect(Collectors.joining(", "));

    private final JdbcTemplate jdbc;
    private final RowMapper<HeldRow> rows = this::row;

    HeldStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a new hold stores; the database adds its id's time, the request time and the expiry. */
    record NewHeld(
            UUID id,
            String type,
            int typeVersion,
            ExecutionMode mode,
            AuthKind authKind,
            String providerId,
            UUID requesterId,
            String requesterUsername,
            UUID tokenId,
            String summary,
            String reason,
            String approverHint,
            Set<Trait> traits,
            String params,
            List<DisplayRow> display,
            Effect effect,
            PolicyRef policy,
            byte[] paramsHash,
            byte[] sealedPayload,
            UUID clusterId,
            UUID environmentId,
            Duration ttl,
            long requestAuditId) {}

    // ---- reads -------------------------------------------------------------------------------------

    UUID newId() {
        return jdbc.queryForObject("SELECT uuidv7()", UUID.class);
    }

    /** The database's clock now, to the microsecond it stores. */
    Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    Optional<HeldRow> get(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM held_operation WHERE id = ?", rows, id).stream()
                .findFirst();
    }

    /** The open request this requester already made with exactly these parameters. */
    Optional<HeldRow> openDuplicate(UUID requesterId, byte[] paramsHash) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM held_operation WHERE requester_id = ? AND params_hash = ?"
                                + " AND state IN (" + OPEN + ")",
                        rows,
                        requesterId,
                        paramsHash)
                .stream()
                .findFirst();
    }

    /** The open requests of one type, newest first. */
    List<HeldRow> openByType(String type, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM held_operation WHERE type = ? AND state IN (" + OPEN
                        + ") ORDER BY id DESC LIMIT ?",
                rows,
                type,
                limit);
    }

    int openCount(UUID requesterId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM held_operation WHERE requester_id = ? AND state IN (" + OPEN + ")",
                Integer.class,
                requesterId);
    }

    /** Newest first, with an id below {@code before} when given (ids are time-ordered). */
    List<HeldRow> list(
            Collection<HeldState> states, UUID requesterId, String providerId, UUID clusterId, UUID before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM held_operation WHERE true");
        List<Object> args = new ArrayList<>();
        if (!states.isEmpty()) {
            sql.append(" AND state IN (")
                    .append(states.stream().map(s -> "?").collect(Collectors.joining(", ")))
                    .append(")");
            states.forEach(s -> args.add(s.name()));
        }
        if (requesterId != null) {
            sql.append(" AND requester_id = ?");
            args.add(requesterId);
        }
        if (providerId != null) {
            sql.append(" AND provider_id = ?");
            args.add(providerId);
        }
        if (clusterId != null) {
            sql.append(" AND cluster_id = ?");
            args.add(clusterId);
        }
        if (before != null) {
            sql.append(" AND id < ?");
            args.add(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), rows, args.toArray());
    }

    List<HeldEvent> timeline(UUID heldId) {
        return jdbc.query(
                "SELECT txid::text, seq, held_id, kind, actor_id, actor_username, detail, at FROM held_operation_event"
                        + " WHERE held_id = ? ORDER BY seq",
                HeldStore::event,
                heldId);
    }

    /**
     * Events of {@code providerId}'s operations after {@code cursor}, in commit order. Only events whose transaction
     * is older than every transaction still running are returned, so one that commits later can never land behind
     * a cursor a reader already moved past.
     */
    List<HeldEvent> eventsAfter(String providerId, EventCursor cursor, int limit) {
        return jdbc.query("""
                SELECT e.txid::text, e.seq, e.held_id, e.kind, e.actor_id, e.actor_username, e.detail, e.at
                FROM held_operation_event e JOIN held_operation h ON h.id = e.held_id
                WHERE h.provider_id = ? AND (e.txid, e.seq) > (?::text::xid8, ?)
                  AND e.txid < pg_snapshot_xmin(pg_current_snapshot())
                ORDER BY e.txid, e.seq
                LIMIT ?""", HeldStore::event, providerId, Long.toString(cursor.txid()), cursor.seq(), limit);
    }

    // ---- writes ------------------------------------------------------------------------------------

    /** Stores a new hold; false when the same requester already has the same request open. */
    boolean insert(NewHeld held) {
        return jdbc.update("""
                        INSERT INTO held_operation (id, type, type_version, mode, auth_kind, provider_id, requester_id,
                            requester_username, token_id, summary, reason, approver_hint, traits, params, display,
                            effect, policy, params_hash, sealed_payload, cluster_id, environment_id, expires_at,
                            request_audit_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?,
                            now() + make_interval(secs => ?), ?)
                        ON CONFLICT (requester_id, params_hash) WHERE state IN ('HELD', 'APPROVED', 'EXECUTING')
                        DO NOTHING""", ps -> {
                    ps.setObject(1, held.id());
                    ps.setString(2, held.type());
                    ps.setInt(3, held.typeVersion());
                    ps.setString(4, held.mode().name());
                    ps.setString(5, held.authKind().name());
                    ps.setString(6, held.providerId());
                    ps.setObject(7, held.requesterId());
                    ps.setString(8, held.requesterUsername());
                    ps.setObject(9, held.tokenId());
                    ps.setString(10, held.summary());
                    ps.setString(11, held.reason());
                    ps.setString(12, held.approverHint());
                    ps.setArray(
                            13,
                            ps.getConnection()
                                    .createArrayOf(
                                            "text",
                                            held.traits().stream()
                                                    .map(Trait::name)
                                                    .sorted()
                                                    .toArray()));
                    ps.setString(14, held.params());
                    ps.setString(15, JSON.writeValueAsString(held.display()));
                    ps.setString(16, JSON.writeValueAsString(held.effect()));
                    ps.setString(17, JSON.writeValueAsString(held.policy()));
                    ps.setBytes(18, held.paramsHash());
                    ps.setBytes(19, held.sealedPayload());
                    ps.setObject(20, held.clusterId());
                    ps.setObject(21, held.environmentId());
                    ps.setDouble(22, held.ttl().toMillis() / 1000.0);
                    ps.setLong(23, held.requestAuditId());
                })
                == 1;
    }

    /**
     * Decides a held request, if it is still held, unexpired and at the version the approver saw. Empty when another
     * decision, a cancellation, an expiry or a change came first.
     */
    Optional<HeldRow> decide(
            UUID id,
            int version,
            HeldState to,
            UUID approverId,
            String approverUsername,
            byte[] sealedDecision,
            Instant decidedAt,
            String decisionReason,
            Duration runWindow) {
        return jdbc
                .query(
                        "UPDATE held_operation SET state = ?, approver_id = ?, approver_username = ?,"
                                + " sealed_decision = ?, decided_at = ?, decision_reason = ?,"
                                + " run_deadline = CASE WHEN ? = 'APPROVED' THEN now() + make_interval(secs => ?) END,"
                                + " finished_at = CASE WHEN ? = 'REJECTED' THEN now() END,"
                                + " sealed_payload = CASE WHEN ? = 'REJECTED' THEN NULL ELSE sealed_payload END"
                                + " WHERE id = ? AND state = 'HELD' AND version = ? AND expires_at > now()"
                                + " RETURNING " + COLUMNS,
                        rows,
                        to.name(),
                        approverId,
                        approverUsername,
                        sealedDecision,
                        Timestamp.from(decidedAt),
                        decisionReason,
                        to.name(),
                        runWindow.toMillis() / 1000.0,
                        to.name(),
                        to.name(),
                        id,
                        version)
                .stream()
                .findFirst();
    }

    /** Claims an approved request to run it; only one claimer ever gets it back. */
    Optional<HeldRow> claim(UUID id, UUID replicaId) {
        return jdbc
                .query(
                        "UPDATE held_operation SET state = 'EXECUTING', claimed_at = now(), claimed_by = ?"
                                + " WHERE id = ? AND state = 'APPROVED' AND run_deadline > now() RETURNING " + COLUMNS,
                        rows,
                        replicaId,
                        id)
                .stream()
                .findFirst();
    }

    /** Ends a request that is in one of {@code from}, wiping its sealed request. */
    Optional<HeldRow> end(UUID id, Collection<HeldState> from, HeldState to, String detail) {
        List<Object> args = new ArrayList<>(List.of(to.name(), truncate(detail), id));
        from.forEach(s -> args.add(s.name()));
        return jdbc
                .query(
                        "UPDATE held_operation SET state = ?, finished_at = now(), outcome_detail = ?,"
                                + " sealed_payload = NULL WHERE id = ? AND state IN ("
                                + from.stream().map(s -> "?").collect(Collectors.joining(", ")) + ") RETURNING "
                                + COLUMNS,
                        rows,
                        args.toArray())
                .stream()
                .findFirst();
    }

    /**
     * Ends up to {@code limit} requests that match {@code where} (over the table's columns, with {@code whereArgs}),
     * skipping rows another writer holds; returns them as ended.
     */
    List<HeldRow> endBatch(String where, List<Object> whereArgs, HeldState to, String detail, int limit) {
        List<Object> args = new ArrayList<>(whereArgs);
        args.add(limit);
        args.add(to.name());
        args.add(truncate(detail));
        return jdbc.query(
                "WITH due AS (SELECT id FROM held_operation WHERE " + where + " LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " UPDATE held_operation h SET state = ?, finished_at = now(), outcome_detail = ?,"
                        + " sealed_payload = NULL FROM due WHERE h.id = due.id RETURNING " + H_COLUMNS,
                rows,
                args.toArray());
    }

    void event(UUID heldId, HeldEvent.Kind kind, UUID actorId, String actorUsername, String detail) {
        jdbc.update(
                "INSERT INTO held_operation_event (held_id, kind, actor_id, actor_username, detail) VALUES (?, ?, ?, ?, ?)",
                heldId,
                kind.name(),
                actorId,
                actorUsername,
                truncate(detail));
    }

    // ---- mapping -----------------------------------------------------------------------------------

    private static String truncate(String detail) {
        return detail == null || detail.length() <= 2000 ? detail : detail.substring(0, 1999) + "…";
    }

    private HeldRow row(ResultSet rs, int i) throws SQLException {
        return new HeldRow(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getInt("type_version"),
                HeldState.valueOf(rs.getString("state")),
                ExecutionMode.valueOf(rs.getString("mode")),
                AuthKind.valueOf(rs.getString("auth_kind")),
                rs.getString("provider_id"),
                rs.getObject("requester_id", UUID.class),
                rs.getString("requester_username"),
                rs.getObject("token_id", UUID.class),
                rs.getObject("approver_id", UUID.class),
                rs.getString("approver_username"),
                rs.getString("summary"),
                rs.getString("reason"),
                rs.getString("approver_hint"),
                rs.getString("decision_reason"),
                rs.getString("outcome_detail"),
                traits(rs.getArray("traits")),
                CanonicalJson.write(JSON.readTree(rs.getString("params"))),
                JSON.readValue(rs.getString("display"), DISPLAY),
                JSON.readValue(rs.getString("effect"), Effect.class),
                JSON.readValue(rs.getString("policy"), PolicyRef.class),
                rs.getBytes("params_hash"),
                rs.getBytes("sealed_payload"),
                rs.getBytes("sealed_decision"),
                rs.getObject("cluster_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                instant(rs, "requested_at"),
                instant(rs, "expires_at"),
                instant(rs, "decided_at"),
                instant(rs, "run_deadline"),
                instant(rs, "claimed_at"),
                rs.getObject("claimed_by", UUID.class),
                instant(rs, "finished_at"),
                rs.getLong("request_audit_id"),
                rs.getInt("version"));
    }

    private static Set<Trait> traits(Array array) throws SQLException {
        Set<Trait> traits = EnumSet.noneOf(Trait.class);
        for (Object name : (Object[]) array.getArray()) {
            traits.add(Trait.valueOf((String) name));
        }
        return traits;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static HeldEvent event(ResultSet rs, int i) throws SQLException {
        return new HeldEvent(
                new EventCursor(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                rs.getObject(3, UUID.class),
                HeldEvent.Kind.valueOf(rs.getString(4)),
                rs.getObject(5, UUID.class),
                rs.getString(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant());
    }
}
