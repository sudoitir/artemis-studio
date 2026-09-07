package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Order;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Bound;
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link QueryPlan} against the historical index (ADR-0059).
 *
 * <p>Every value the operator typed becomes a bind parameter. The SQL text is
 * assembled only from the {@link ColumnCatalogue}'s own column names and fixed
 * operator spellings, so no fragment of the query string reaches Postgres as SQL.
 * The statement runs read-only with its own timeout, so a pathological predicate
 * costs one query rather than the connection pool.
 */
@Component
@RequiredArgsConstructor
public class IndexQueryExecutor {

    private final JdbcTemplate jdbc;
    private final ArtemisStudioProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    /** A fragment plus the values that fill its placeholders. */
    private record Sql(String text, List<Object> binds) {}

    @Transactional(readOnly = true)
    public QueryResult execute(UUID clusterId, QueryPlan plan, BrokerQueryExecutor.Sink sink) {
        List<Object> binds = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT observed_at, last_seen_at, message_id, timestamp_ms, expiration_ms, size_bytes,
                       priority, message_type, queue_name, address, node_name, correlation_id, group_id,
                       user_id, reply_to, jms_type, body, props, cluster_id, node_id, durable
                  FROM message_index
                 WHERE cluster_id = ?
                """);
        binds.add(clusterId);

        List<String> queues =
                plan.targets().stream().map(Target::queueName).distinct().toList();
        if (!queues.isEmpty()) {
            sql.append(" AND queue_name IN (")
                    .append(String.join(", ", java.util.Collections.nCopies(queues.size(), "?")))
                    .append(')');
            binds.addAll(queues);
        }

        if (plan.ast().where() != null) {
            Sql where = predicate(plan.ast().where());
            sql.append(" AND (").append(where.text()).append(')');
            binds.addAll(where.binds());
        }

        sql.append(orderBy(plan.ast().orderBy()));
        sql.append(" LIMIT ?");
        binds.add(plan.effectiveLimit() + 1);

        jdbc.execute("SET LOCAL statement_timeout = "
                + Math.max(1000, properties.sql().timeout().toMillis()));

        List<Row> rows = jdbc.query(sql.toString(), this::toRow, binds.toArray());

        List<Bound> bounds = new ArrayList<>();
        if (rows.size() > plan.effectiveLimit()) {
            rows = rows.subList(0, plan.effectiveLimit());
            bounds.add(new Bound(Bound.Kind.ROW_LIMIT, plan.effectiveLimit()));
        }
        rows.forEach(sink::row);

        // The index answers in one statement, so there is no per-node fan-out to
        // report — but the queues it covered are still named, so a result from the
        // index and one from the brokers read the same way.
        List<NodeOutcome> outcomes = new ArrayList<>();
        Map<String, Long> perQueue = new LinkedHashMap<>();
        for (Row row : rows) {
            perQueue.merge(row.queueName(), 1L, Long::sum);
        }
        for (Target target : plan.targets()) {
            long matched = perQueue.getOrDefault(target.queueName(), 0L);
            NodeOutcome outcome = new NodeOutcome(
                    target.nodeId(),
                    target.nodeName(),
                    target.queueName(),
                    NodeOutcome.Status.ANSWERED,
                    matched,
                    matched,
                    null,
                    null);
            outcomes.add(outcome);
            sink.nodeFinished(outcome);
        }
        return new QueryResult(List.copyOf(rows), List.copyOf(outcomes), List.copyOf(bounds), plan.notices());
    }

    // ---- predicate compilation -----------------------------------------

    private Sql predicate(Predicate predicate) {
        return switch (predicate) {
            case Predicate.And and -> join(and.parts(), " AND ");
            case Predicate.Or or -> join(or.parts(), " OR ");
            case Predicate.Not not -> {
                Sql inner = predicate(not.inner());
                yield new Sql("NOT (" + inner.text() + ')', inner.binds());
            }
            case Predicate.Compare compare -> compare(compare);
            case Predicate.In in -> in(in);
            case Predicate.IsNull isNull -> {
                Sql term = term(isNull.term());
                yield new Sql(term.text() + (isNull.negated() ? " IS NOT NULL" : " IS NULL"), term.binds());
            }
            case Predicate.Like like -> like(like);
            case Predicate.Between between -> between(between);
        };
    }

    private Sql join(List<Predicate> parts, String operator) {
        List<String> fragments = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (Predicate part : parts) {
            Sql compiled = predicate(part);
            fragments.add('(' + compiled.text() + ')');
            binds.addAll(compiled.binds());
        }
        return new Sql(String.join(operator, fragments), binds);
    }

    private Sql compare(Predicate.Compare compare) {
        Sql term = term(compare.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(value(compare.value(), compare.term()));
        return new Sql(term.text() + " " + compare.op().sql() + " ?", binds);
    }

    private Sql in(Predicate.In in) {
        Sql term = term(in.term());
        List<Object> binds = new ArrayList<>(term.binds());
        in.values().forEach(v -> binds.add(value(v, in.term())));
        String placeholders =
                String.join(", ", java.util.Collections.nCopies(in.values().size(), "?"));
        return new Sql(term.text() + (in.negated() ? " NOT IN (" : " IN (") + placeholders + ')', binds);
    }

    private Sql between(Predicate.Between between) {
        Sql term = term(between.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(value(between.low(), between.term()));
        binds.add(value(between.high(), between.term()));
        return new Sql(term.text() + (between.negated() ? " NOT BETWEEN ? AND ?" : " BETWEEN ? AND ?"), binds);
    }

    private Sql like(Predicate.Like like) {
        Sql term = term(like.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(like.pattern());
        String operator = like.caseInsensitive() ? " ILIKE ?" : " LIKE ?";
        String escape = like.escape() == null ? "" : " ESCAPE ?";
        if (like.escape() != null) {
            binds.add(String.valueOf(like.escape()));
        }
        String text = term.text() + (like.negated() ? " NOT" : "") + operator + escape;
        return new Sql(text, binds);
    }

    // ---- terms ----------------------------------------------------------

    /**
     * A term becomes a column name from the catalogue or a JSONB path expression. The
     * only operator-supplied text that appears is a property or JSON key, and it goes
     * in as a bind, never as an identifier.
     */
    private Sql term(Term term) {
        return switch (term) {
            case Term.ColumnTerm column -> new Sql(columnName(column.column()), List.of());
            case Term.PropertyTerm property -> new Sql("props ->> ?", List.of(property.name()));
            case Term.JsonTerm path -> new Sql("(body::jsonb #>> string_to_array(?, '.'))", List.of(path.path()));
            case Term.CaseFold fold -> {
                Sql inner = term(fold.inner());
                yield new Sql((fold.upper() ? "upper(" : "lower(") + inner.text() + ')', inner.binds());
            }
        };
    }

    private String columnName(Column column) {
        return switch (column) {
            case MESSAGE_ID -> "message_id";
            case QUEUE -> "queue_name";
            case ADDRESS -> "address";
            case NODE -> "node_name";
            case PRIORITY -> "priority";
            case DURABLE -> "durable";
            case TIMESTAMP -> "timestamp_ms";
            case EXPIRATION -> "expiration_ms";
            case SIZE -> "size_bytes";
            case JMS_TYPE -> "jms_type";
            case CORRELATION_ID -> "correlation_id";
            case GROUP_ID -> "group_id";
            case USER_ID -> "user_id";
            case MESSAGE_TYPE -> "message_type";
            case REPLY_TO -> "reply_to";
            case BODY -> "body";
            case OBSERVED_AT -> "observed_at";
            case LAST_SEEN_AT -> "last_seen_at";
        };
    }

    /**
     * A literal's bind value. A relative time against a millisecond column binds
     * milliseconds; against a timestamptz column it binds an instant — the same
     * predicate means the same thing either way, which is the point.
     */
    private Object value(Literal literal, Term against) {
        return switch (literal) {
            case Literal.Str str -> str.value();
            case Literal.Num num -> num.integral() ? (Object) (long) num.value() : num.value();
            case Literal.Bool bool -> bool.value();
            case Literal.RelativeTime relative -> {
                Instant at = Instant.now().minus(relative.before());
                yield isEpochMillisColumn(against) ? (Object) at.toEpochMilli() : Timestamp.from(at);
            }
        };
    }

    private boolean isEpochMillisColumn(Term term) {
        if (!(term instanceof Term.ColumnTerm column)) {
            return false;
        }
        return column.column() == Column.TIMESTAMP || column.column() == Column.EXPIRATION;
    }

    private String orderBy(List<Order> orders) {
        if (orders.isEmpty()) {
            return " ORDER BY observed_at DESC";
        }
        List<String> fragments = new ArrayList<>();
        for (Order order : orders) {
            if (order.term() instanceof Term.ColumnTerm column) {
                fragments.add(columnName(column.column())
                        + (order.direction() == QueryAst.Direction.DESC ? " DESC" : " ASC"));
            }
        }
        return fragments.isEmpty() ? " ORDER BY observed_at DESC" : " ORDER BY " + String.join(", ", fragments);
    }

    // ---- mapping --------------------------------------------------------

    private Row toRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("node_id", UUID.class),
                rs.getString("node_name"),
                rs.getString("queue_name"),
                rs.getString("address"),
                rs.getLong("message_id"),
                rs.getInt("message_type"),
                rs.getBoolean("durable"),
                rs.getInt("priority"),
                rs.getLong("timestamp_ms"),
                rs.getLong("expiration_ms"),
                rs.getLong("size_bytes"),
                rs.getString("jms_type"),
                rs.getString("correlation_id"),
                rs.getString("group_id"),
                rs.getString("user_id"),
                rs.getString("reply_to"),
                rs.getString("body"),
                false,
                properties(rs.getString("props")),
                Source.INDEX,
                instant(rs.getTimestamp("observed_at")),
                instant(rs.getTimestamp("last_seen_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(rawJson, Map.class);
        } catch (JacksonException e) {
            return new HashMap<>();
        }
    }
}
