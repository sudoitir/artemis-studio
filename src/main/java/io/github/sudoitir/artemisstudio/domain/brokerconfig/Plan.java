package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an apply would do, computed from the declaration and one observed read per
 * node (ADR-0067 D3). The same computation feeds the drift report: a step that would
 * apply is a finding when nobody is about to apply it.
 *
 * <p>{@code planHash} covers the ordered list of steps that would write something, so
 * a real run can refuse when the cluster moved since the preview (D12). Hazards and
 * findings are outside the hash: they are consequences of the steps, not the steps.
 */
public record Plan(
        List<NodePlan> nodes,
        List<Hazard> hazards,
        List<Finding> findings,
        List<Violation> violations,
        String planHash,
        int stepCount,
        UUID canaryNodeId) {

    public Plan {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        hazards = List.copyOf(hazards == null ? List.of() : hazards);
        findings = List.copyOf(findings == null ? List.of() : findings);
        violations = List.copyOf(violations == null ? List.of() : violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }

    /** The High hazard identifiers a real run must acknowledge. */
    public List<String> highHazardIds() {
        return hazards.stream()
                .filter(h -> h.hazardClass() == HazardClass.HIGH)
                .map(Hazard::id)
                .toList();
    }

    /** One node's ordered steps. A node that is not live or not readable has none. */
    public record NodePlan(UUID nodeId, String nodeName, boolean live, String unavailableReason, List<Step> steps) {
        public NodePlan {
            steps = List.copyOf(steps == null ? List.of() : steps);
        }

        public boolean readable() {
            return live && unavailableReason == null;
        }

        /** Steps that would write something. */
        public long pendingSteps() {
            return steps.stream().filter(s -> !s.already()).count();
        }
    }

    /**
     * One management write on one node. {@code before} and {@code after} are the
     * values as the broker reports them — for an address setting, every key the
     * merged entry carries, not only the declared ones (D5). {@code already} means the
     * observed state matches and nothing will be written.
     */
    public record Step(
            String id,
            Op op,
            Section section,
            String key,
            Map<String, Object> before,
            Map<String, Object> after,
            boolean already,
            String description) {
        public Step {
            before = before == null ? Map.of() : Map.copyOf(before);
            after = after == null ? Map.of() : Map.copyOf(after);
        }
    }

    public enum Op {
        ADD,
        REPLACE,
        REMOVE
    }

    public enum Section {
        ADDRESS,
        QUEUE,
        ADDRESS_SETTING,
        SECURITY_SETTING,
        DIVERT
    }

    /** A consequence the operator is told about, in words, with a stable identifier (D7). */
    public record Hazard(
            String id,
            HazardKind kind,
            HazardClass hazardClass,
            UUID nodeId,
            String nodeName,
            Section section,
            String key,
            String message) {}

    public enum HazardKind {
        UNINTENDED_KEY_CHANGE,
        MESSAGE_LOSS_POLICY,
        BLOCKING_POLICY,
        LIMIT_BELOW_USAGE,
        MANAGEMENT_ACCESS,
        BROAD_MATCH,
        EXCLUSIVE_DIVERT,
        DIVERT_REPLACE,
        DLQ_EXPIRY_CHANGE,
        AUTO_DELETE_ENABLED,
        REMOVE_OWNED,
        REMOVE_UNDECLARED,
        REDISTRIBUTION_CHANGE
    }

    /**
     * Something the plan noticed and will not act on: a queue that exists with a
     * different configuration, an undeclared resource, a node that could not be read.
     */
    public record Finding(FindingKind kind, UUID nodeId, String nodeName, Section section, String key, String detail) {}

    public enum FindingKind {
        MISSING,
        DIVERGENT,
        UNDECLARED,
        DIVERGENT_QUEUE,
        DIVERGENT_ADDRESS,
        NOT_EVALUATED,
        UNREACHABLE
    }
}
