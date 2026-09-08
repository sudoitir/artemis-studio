package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.CaptureState;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers what the index can and cannot speak for (ADR-0059 D3).
 *
 * <p>The failure this exists to prevent is the quiet one: a query that reaches
 * before a subscription started capturing, or names a queue nothing captures,
 * returning a short list that the operator reads as the answer. Every gap here
 * becomes a {@link Notice} on the plan, so the shortfall is stated rather than
 * inferred.
 */
@Component
@RequiredArgsConstructor
public class MessageIndexCoverage {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final QueueSnapshotRepository snapshots;

    @Transactional(readOnly = true)
    public boolean isIndexed(UUID clusterId, String queueName) {
        return subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .anyMatch(s -> QueueNamePattern.matches(s.getQueuePattern(), queueName));
    }

    /**
     * Whether every target is being captured on the node it sits on. Per node and
     * per target, because that is the granularity capture actually has: one node
     * refusing the tap makes the whole result partly sampled, and averaging over
     * nodes would let the console make a claim that is false for one of them.
     */
    @Transactional(readOnly = true)
    public boolean isCaptured(UUID clusterId, List<Target> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        List<MessageIndexSubscriptionEntity> capturing = subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .filter(s -> s.getMode() == CaptureMode.CAPTURE)
                .toList();
        return targets.stream()
                .allMatch(target -> capturing.stream()
                        .filter(s -> QueueNamePattern.matches(s.getQueuePattern(), target.queueName()))
                        .anyMatch(s -> captureNodes
                                .findBySubscriptionIdAndNodeId(s.getId(), target.nodeId())
                                .map(MessageCaptureNodeEntity::getCaptureState)
                                .filter(state -> state == CaptureState.ACTIVE)
                                .isPresent()));
    }

    /** Every way this query reaches outside what the index holds. */
    @Transactional(readOnly = true)
    public List<Notice> check(UUID clusterId, QueryAst ast, List<Target> targets) {
        List<MessageIndexSubscriptionEntity> enabled = subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .toList();
        List<Notice> notices = new ArrayList<>();

        List<String> uncovered = targets.stream()
                .map(Target::queueName)
                .distinct()
                .filter(queue -> enabled.stream().noneMatch(s -> QueueNamePattern.matches(s.getQueuePattern(), queue)))
                .toList();
        if (!uncovered.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.INDEX_COVERAGE_GAP,
                    "The index holds nothing for " + String.join(", ", uncovered) + " — no subscription captures "
                            + (uncovered.size() == 1 ? "it." : "them.")));
        }

        reach(ast.where()).ifPresent(window -> {
            Instant earliestCapture = enabled.stream()
                    .map(MessageIndexSubscriptionEntity::getCaptureFrom)
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
            Instant retentionFloor = enabled.stream()
                    .map(s -> Instant.now().minus(Duration.ofDays(s.getRetentionDays())))
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
            Instant floor = earliestCapture.isAfter(retentionFloor) ? earliestCapture : retentionFloor;
            Instant asked = Instant.now().minus(window);
            if (asked.isBefore(floor)) {
                notices.add(
                        new Notice(
                                Notice.Kind.INDEX_COVERAGE_GAP,
                                "The index has no coverage before " + floor
                                        + " — the window asked for reaches further back than capture began or retention keeps."));
            }
        });
        notices.addAll(captureNotices(clusterId, enabled, targets));
        return List.copyOf(notices);
    }

    /**
     * The two things a captured result cannot say for itself.
     *
     * <p><b>Per node.</b> Capture is installed node by node, and a node that refused
     * it, or was promoted after a failover and not yet reached, contributes nothing.
     * Rolling that up into one "indexed" answer would report a partial result as a
     * complete one, so the nodes it cannot speak for are named (ADR-0062 D4).
     *
     * <p><b>Per address.</b> A divert copies at address routing, before multicast
     * fan-out, so for an address with more than one bound queue the index holds one
     * row per routed message and genuinely cannot attribute it to a subscription
     * queue. Anycast is unaffected: address and queue coincide.
     */
    private List<Notice> captureNotices(
            UUID clusterId, List<MessageIndexSubscriptionEntity> enabled, List<Target> targets) {
        List<MessageIndexSubscriptionEntity> capturing =
                enabled.stream().filter(s -> s.getMode() == CaptureMode.CAPTURE).toList();
        if (capturing.isEmpty() || targets.isEmpty()) {
            return List.of();
        }
        List<Notice> notices = new ArrayList<>();

        List<String> uncoveredNodes = new ArrayList<>();
        for (Target target : targets) {
            boolean covered = capturing.stream()
                    .filter(s -> QueueNamePattern.matches(s.getQueuePattern(), target.queueName()))
                    .anyMatch(s -> captureNodes
                            .findBySubscriptionIdAndNodeId(s.getId(), target.nodeId())
                            .map(MessageCaptureNodeEntity::getCaptureState)
                            .filter(state -> state == CaptureState.ACTIVE)
                            .isPresent());
            if (!covered && !uncoveredNodes.contains(target.nodeName())) {
                uncoveredNodes.add(target.nodeName());
            }
        }
        if (!uncoveredNodes.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.CAPTURE_NODE_GAP,
                    "Not capturing on " + String.join(", ", uncoveredNodes)
                            + ", so this result is complete only for the nodes that are."));
        }

        List<String> fannedOut = targets.stream()
                .map(Target::address)
                .distinct()
                .filter(address -> address != null && boundQueueCount(clusterId, address) > 1)
                .toList();
        if (!fannedOut.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.ADDRESS_SCOPED_CAPTURE,
                    String.join(", ", fannedOut) + (fannedOut.size() == 1 ? " has" : " have")
                            + " more than one queue bound."));
        }
        return notices;
    }

    private long boundQueueCount(UUID clusterId, String address) {
        return snapshots.findByClusterId(clusterId).stream()
                .filter(row -> address.equals(row.getAddress()))
                .map(QueueSnapshotEntity::getQueueName)
                .distinct()
                .count();
    }

    /** How far back a relative time predicate asks the index to look, if it does. */
    private java.util.Optional<Duration> reach(Predicate predicate) {
        if (predicate == null) {
            return java.util.Optional.empty();
        }
        return switch (predicate) {
            case Predicate.And and -> widest(and.parts());
            case Predicate.Or or -> widest(or.parts());
            case Predicate.Not not -> reach(not.inner());
            case Predicate.Compare compare ->
                isTimeTerm(compare.term()) && compare.value() instanceof Literal.RelativeTime relative
                        ? java.util.Optional.of(relative.before())
                        : java.util.Optional.empty();
            case Predicate.Between between ->
                isTimeTerm(between.term()) && between.low() instanceof Literal.RelativeTime relative
                        ? java.util.Optional.of(relative.before())
                        : java.util.Optional.empty();
            case Predicate.In ignored -> java.util.Optional.empty();
            case Predicate.IsNull ignored -> java.util.Optional.empty();
            case Predicate.Like ignored -> java.util.Optional.empty();
            case Predicate.Match ignored -> java.util.Optional.empty();
        };
    }

    private java.util.Optional<Duration> widest(List<Predicate> parts) {
        return parts.stream()
                .map(this::reach)
                .flatMap(java.util.Optional::stream)
                .max(Duration::compareTo);
    }

    private boolean isTimeTerm(Term term) {
        return term instanceof Term.ColumnTerm column && column.column().type() == ColumnCatalogue.Type.TIMESTAMP;
    }
}
