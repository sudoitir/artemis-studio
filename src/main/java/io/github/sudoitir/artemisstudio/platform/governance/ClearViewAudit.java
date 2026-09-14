package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evidence that sensitive values were served in clear (data-governance spec): one audit row per
 * response, carrying classes and counts, never a value. Its own transaction, because reads run
 * read-only.
 */
@Component
@RequiredArgsConstructor
public class ClearViewAudit {

    public static final String ACTION = "VIEW_CLEAR";

    private final AuditService audit;
    private final ActorResolver actors;

    /** Record the clear values in {@code messages}; nothing is written when none were served clear. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            GovernContext context, String targetType, String targetName, Collection<GovernedMessage> messages) {
        Map<String, Long> classes = classesServedClear(messages);
        if (classes.isEmpty()) {
            return;
        }
        long total = classes.values().stream().mapToLong(Long::longValue).sum();
        AuditEvent event = audit.begin(
                actors.resolve(),
                ACTION,
                targetType,
                targetName,
                context.clusterId(),
                null,
                Map.of("classes", classes, "messages", messages.size()),
                false);
        audit.succeed(event, total);
    }

    /**
     * Record classes and counts already tallied, for an actor resolved earlier — a stream that ends on a thread
     * with no request, such as a tail closing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            io.github.sudoitir.artemisstudio.kernel.security.Actor actor,
            GovernContext context,
            String targetType,
            String targetName,
            Map<String, Long> classes,
            long messages) {
        if (classes.isEmpty()) {
            return;
        }
        long total = classes.values().stream().mapToLong(Long::longValue).sum();
        AuditEvent event = audit.begin(
                actor,
                ACTION,
                targetType,
                targetName,
                context.clusterId(),
                null,
                Map.of("classes", classes, "messages", messages),
                false);
        audit.succeed(event, total);
    }

    /** Class name to count of values served clear, for callers that fold them into their own audit row. */
    public static Map<String, Long> classesServedClear(Collection<GovernedMessage> messages) {
        Map<String, Long> classes = new TreeMap<>();
        for (GovernedMessage message : messages) {
            for (GovernedMessage.Redaction r : message.redactions()) {
                if (r.clear()) {
                    classes.merge(r.dataClass().name(), 1L, Long::sum);
                }
            }
        }
        return classes;
    }
}
