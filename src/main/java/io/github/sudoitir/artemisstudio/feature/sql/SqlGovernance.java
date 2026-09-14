package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.messages.MessageService;
import io.github.sudoitir.artemisstudio.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Term;
import io.github.sudoitir.artemisstudio.feature.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.GovernContext;
import io.github.sudoitir.artemisstudio.platform.governance.GovernedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.Location;
import io.github.sudoitir.artemisstudio.platform.governance.MessageContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The content policy applied to the SQL console (sql-console spec): governed result rows, residual
 * predicates evaluated over masked content, and a refusal for predicates on masked fields.
 */
@Component
@RequiredArgsConstructor
public class SqlGovernance {

    /** Artemis' BYTES message type; its body is binary and cannot be classified. */
    static final int BYTES_MESSAGE = 4;

    /** The catalogue columns that are message headers, by the header name the policy knows them as. */
    private static final Map<Column, String> HEADER_COLUMNS = Map.of(
            Column.CORRELATION_ID, "correlationId",
            Column.GROUP_ID, "groupId",
            Column.USER_ID, "userId",
            Column.REPLY_TO, "replyTo");

    private final ContentPolicy policy;

    /** Whether the current caller holds {@code message:clear} on the cluster. Only meaningful on a request thread. */
    public boolean clearAccess(UUID clusterId) {
        return policy.context(clusterId, null).clearAccess();
    }

    /** A result row as the caller may see it. */
    public GovernedMessage govern(UUID clusterId, boolean clearAccess, Row row) {
        return policy.govern(new GovernContext(clusterId, row.address(), clearAccess), content(row));
    }

    /** A row as Studio may store it: masked for everyone, with sealable originals collected. */
    public GovernedMessage forStorage(UUID clusterId, Row row) {
        return forStorage(clusterId, row.address(), content(row));
    }

    public GovernedMessage forStorage(UUID clusterId, String address, MessageContent content) {
        return policy.governForStorage(clusterId, address, content);
    }

    /** The row with its headers, properties and body replaced by {@code content}'s. */
    static Row withContent(Row row, MessageContent content) {
        return new Row(
                row.nodeId(),
                row.nodeName(),
                row.queueName(),
                row.address(),
                row.messageId(),
                row.messageType(),
                row.durable(),
                row.priority(),
                row.timestamp(),
                row.expiration(),
                row.size(),
                row.jmsType(),
                content.headers().get("correlationId"),
                content.headers().get("groupId"),
                content.headers().get("userId"),
                content.headers().get("replyTo"),
                content.body(),
                row.bodyTruncated(),
                content.properties(),
                row.source(),
                row.observedAt(),
                row.lastSeenAt(),
                row.origin(),
                row.sourceMessageId());
    }

    static MessageContent content(Row row) {
        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", row.correlationId());
        headers.put("groupId", row.groupId());
        headers.put("userId", row.userId());
        headers.put("replyTo", row.replyTo());
        return new MessageContent(headers, row.properties(), row.body(), row.messageType() == BYTES_MESSAGE, null);
    }

    /**
     * The message a residual predicate is evaluated against for a caller without clear access: the masked
     * copy, so a body search cannot confirm a value the caller would only ever see masked.
     */
    public BrowsedMessage forEvaluation(UUID clusterId, String address, BrowsedMessage m) {
        GovernedMessage g = policy.govern(GovernContext.masked(clusterId, address), MessageService.content(m));
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        g.properties().forEach((name, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof Long l && m.intProperties().containsKey(name)) {
                ints.put(name, l);
            } else if (value instanceof Long l && m.longProperties().containsKey(name)) {
                longs.put(name, l);
            } else if (value instanceof Double d) {
                doubles.put(name, d);
            } else if (value instanceof Boolean b) {
                booleans.put(name, b);
            } else {
                strings.put(name, String.valueOf(value));
            }
        });
        return new BrowsedMessage(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                g.headers().get("groupId"),
                g.headers().get("correlationId"),
                g.headers().get("replyTo"),
                g.headers().get("userId"),
                g.body(),
                m.bodyEncoding(),
                m.contentType(),
                m.bodyTruncated(),
                m.observedLimitBytes(),
                strings,
                ints,
                longs,
                doubles,
                booleans);
    }

    /**
     * Refuse a query whose predicate or ordering names a field a masking rule covers, unless the caller holds
     * clear access. Ordering counts: sorting by a masked value reveals how the hidden values compare.
     */
    public void guardPredicates(UUID clusterId, QueryAst ast) {
        String field = maskedField(ast);
        if (field != null && !clearAccess(clusterId)) {
            throw new GovernanceRefusedException(field);
        }
    }

    /** The first field a predicate or ordering names that a masking rule covers, or null. */
    public String maskedField(QueryAst ast) {
        List<Term> terms = new ArrayList<>();
        collect(ast.where(), terms);
        if (ast.orderBy() != null) {
            ast.orderBy().forEach(order -> terms.add(order.term()));
        }
        for (Term term : terms) {
            String field = classifiedField(term);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    /**
     * An index query that names a masked field says so (message-index spec): the index holds the masked value,
     * so the predicate compares masked text. Only a caller with clear access reaches this; others were refused.
     */
    public QueryPlan withAtRestNotice(QueryPlan plan) {
        if (plan.resolvedSource() != QueryAst.Source.INDEX) {
            return plan;
        }
        String field = maskedField(plan.ast());
        if (field == null) {
            return plan;
        }
        List<QueryPlan.Notice> notices = new ArrayList<>(plan.notices());
        notices.add(new QueryPlan.Notice(
                QueryPlan.Notice.Kind.MASKED_AT_REST,
                "The index stores " + field + " masked, so this predicate compares masked text and cannot match an"
                        + " original value. Query the live broker to match the originals."));
        return new QueryPlan(
                plan.ast(),
                plan.resolvedSource(),
                plan.targets(),
                plan.selector(),
                plan.requiresScan(),
                plan.pushedDown(),
                plan.scanned(),
                plan.estimatedMessagesExamined(),
                plan.effectiveLimit(),
                plan.captured(),
                List.copyOf(notices));
    }

    private static void collect(Predicate predicate, List<Term> out) {
        if (predicate == null) {
            return;
        }
        switch (predicate) {
            case Predicate.And and -> and.parts().forEach(p -> collect(p, out));
            case Predicate.Or or -> or.parts().forEach(p -> collect(p, out));
            case Predicate.Not not -> collect(not.inner(), out);
            case Predicate.Compare compare -> out.add(compare.term());
            case Predicate.In in -> out.add(in.term());
            case Predicate.IsNull isNull -> out.add(isNull.term());
            case Predicate.Like like -> out.add(like.term());
            case Predicate.Between between -> out.add(between.term());
            // Full-text search runs over the stored body, which holds only masked values.
            case Predicate.Match ignored -> {}
        }
    }

    private String classifiedField(Term term) {
        return switch (term) {
            case Term.PropertyTerm property ->
                policy.classifies(Location.PROPERTY, property.name()) ? "props." + property.name() : null;
            case Term.JsonTerm json ->
                policy.classifies(Location.BODY, json.path()) ? "body->>'" + json.path() + "'" : null;
            case Term.ColumnTerm column -> {
                String header = HEADER_COLUMNS.get(column.column());
                yield header != null && policy.classifies(Location.HEADER, header)
                        ? column.column().sqlName()
                        : null;
            }
            case Term.CaseFold fold -> classifiedField(fold.inner());
            case Term.MatchRank ignored -> null;
        };
    }
}
