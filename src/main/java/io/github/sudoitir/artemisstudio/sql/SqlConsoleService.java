package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.JolokiaMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageTransport;
import io.github.sudoitir.artemisstudio.broker.core.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The console's entry point: permission, plan, cost gate, execute, audit
 * (ADR-0058).
 *
 * <p>Planning and running are separate operations on purpose. Planning contacts
 * nothing, so the editor can call it on every keystroke; running is the one that
 * costs a broker something, and it is the one that is gated and audited.
 */
@Service
@RequiredArgsConstructor
public class SqlConsoleService {

    private final SqlQueryParser parser;
    private final QueryPlanner planner;
    private final BrokerQueryExecutor brokerExecutor;
    private final IndexQueryExecutor indexExecutor;
    private final JolokiaMessageTransport jolokiaTransport;
    private final CoreMessageTransport coreTransport;
    private final CoreSubscriptionManager subscriptions;
    private final ClusterAccessGuard clusterAccess;
    private final ActorResolver actorResolver;
    private final AuditService audit;
    private final ArtemisStudioProperties properties;

    /** One counter per actor, so one operator cannot occupy the whole fan-out budget. */
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** Parse, validate and cost a query. Contacts no broker and no database beyond the cache. */
    public QueryPlan plan(UUID clusterId, String sql) {
        clusterAccess.requireCluster(clusterId, Permissions.MESSAGE_READ);
        return planner.plan(clusterId, parser.parse(sql));
    }

    /**
     * Run a query. The cost ceiling is enforced here and not in {@link #plan}, so the
     * console can show an over-budget estimate while the operator is still able to
     * narrow it.
     */
    public Executed run(UUID clusterId, String sql, BrokerQueryExecutor.Sink sink) {
        clusterAccess.requireCluster(clusterId, Permissions.MESSAGE_READ);
        QueryPlan plan = planner.plan(clusterId, parser.parse(sql));
        planner.enforceCostCeiling(plan);

        Actor actor = actorResolver.resolve();
        String key = actor == null ? "anonymous" : actor.displayName();
        AtomicInteger running = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        if (running.incrementAndGet() > properties.sql().maxConcurrentQueries()) {
            running.decrementAndGet();
            throw new TooManyQueriesException(properties.sql().maxConcurrentQueries());
        }

        AuditEventEntity event = audit.begin(
                actor,
                "sql.query",
                "CLUSTER",
                plan.ast().queuePattern(),
                clusterId,
                null,
                Map.of(
                        "sql", plan.ast().text(),
                        "source", plan.resolvedSource().name(),
                        "targets", plan.targets().size(),
                        "requiresScan", plan.requiresScan()),
                false);
        try {
            QueryResult result = plan.resolvedSource() == Source.INDEX
                    ? indexExecutor.execute(clusterId, plan, sink)
                    : brokerExecutor.execute(clusterId, plan, transportFor(clusterId), sink);
            // Failed only when a node failed. Reaching a bound — the row limit above
            // all — is an ordinary bounded query, and recording every `LIMIT 5` as a
            // failure would leave an audit log nobody can read for real failures.
            boolean anyNodeFailed =
                    result.nodes().stream().anyMatch(n -> n.status() == QueryResult.NodeOutcome.Status.FAILED);
            audit.finish(
                    event,
                    anyNodeFailed,
                    result.rows().size(),
                    anyNodeFailed ? "a node did not answer: " + describeBounds(result) : null,
                    Map.of(
                            "rows", result.rows().size(),
                            "nodes", result.nodes().size(),
                            "partial", result.isPartial(),
                            "bounds", result.boundsReached()));
            return new Executed(plan, result);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        } finally {
            running.decrementAndGet();
        }
    }

    private String describeBounds(QueryResult result) {
        if (result.boundsReached().isEmpty()) {
            return "a node did not answer";
        }
        return result.boundsReached().stream()
                .map(b -> b.kind().name())
                .reduce((a, b) -> a + ", " + b)
                .orElse("bounded");
    }

    /** Core when the cluster has a live Core subscription (ADR-0029); Jolokia otherwise. */
    public MessageTransport transportFor(UUID clusterId) {
        return subscriptions.verdictFor(clusterId).isConnected() ? coreTransport : jolokiaTransport;
    }

    /** A run, with the plan it ran — the console shows both, and they must be the same one. */
    public record Executed(QueryPlan plan, QueryResult result) {}

    /** One operator, too many queries at once. */
    public static class TooManyQueriesException extends RuntimeException {
        private final int cap;

        public TooManyQueriesException(int cap) {
            super("You already have " + cap + " queries running. Wait for one to finish, or stop it.");
            this.cap = cap;
        }

        public int cap() {
            return cap;
        }
    }
}
