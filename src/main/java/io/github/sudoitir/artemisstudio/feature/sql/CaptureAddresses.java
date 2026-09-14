package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.routing.RoutingService;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The addresses a capture subscription covers — the one answer the reconciler, the loss
 * measurement, the footprint, deletion and retention all use.
 *
 * <p>Captured rows are stored under the address they were routed to (ADR-0062 D2), not under
 * the names of the queues bound to it. Matching them by queue name made every multicast
 * address, and every queue named differently from its address, invisible to footprint,
 * deletion and retention, and counted every message on it as lost.
 *
 * <p>Studio's own capture addresses are never covered (ADR-0079): a pattern that would match
 * them would tap the taps and grow the broker's objects on every pass.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureAddresses {

    private final QueryPlanner planner;
    private final SqlQueryParser parser;

    /**
     * Resolved through the same planner an operator's own query uses, so "which queues does
     * this pattern mean" has exactly one answer in the product. One address however many queues
     * are bound to it: a divert copies at address routing (D3).
     */
    public Set<String> of(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        QueryPlan plan;
        try {
            plan = planner.plan(
                    clusterId, parser.parse("SELECT * FROM broker.\"" + subscription.getQueuePattern() + '"'));
        } catch (RuntimeException e) {
            log.debug("Capture pattern '{}' could not be resolved: {}", subscription.getQueuePattern(), e.getMessage());
            return Set.of();
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (QueryPlan.Target target : plan.targets()) {
            String address = target.address() == null ? target.queueName() : target.address();
            if (!isCaptureObject(address)) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    /** Whether a name belongs to Studio's own capture objects, which no capture may cover. */
    public static boolean isCaptureObject(String name) {
        return name != null && name.startsWith(RoutingService.CAPTURE_DIVERT_PREFIX);
    }
}
