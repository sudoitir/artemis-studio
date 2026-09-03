package io.github.sudoitir.artemisstudio.broker;

/**
 * What a single broker connection can and cannot do, one assessment per
 * capability class (ADR-0002 non-negotiable #5). Every gap carries a reason and,
 * where a {@code broker.xml} change would close it, the exact snippet.
 *
 * <p>Phase 1 determines these over Jolokia only:
 *
 * <ul>
 *   <li>{@code managementRead} — a broker attribute read returned 200.
 *   <li>{@code managementWrite} — <em>inferred</em> from a read-only
 *       {@code listNetworkTopology()} exec succeeding. This proves Jolokia is not
 *       under a read-only policy and the caller cleared {@code manage}; it does
 *       not prove every operation is allowed, because a {@code jolokia-access.xml}
 *       can still whitelist per operation. The reason string says so.
 *   <li>{@code notifications} — always {@link CapabilityStatus#UNKNOWN}: verifying
 *       it needs the Core client (Phase 4). The reason reports the two
 *       preconditions that <em>are</em> visible over Jolokia — a CORE acceptor and
 *       the {@code activemq.notifications} address — and ships both required
 *       {@code broker.xml} snippets.
 *   <li>{@code messageIo} — available but degraded whenever {@code managementWrite}
 *       holds: Jolokia {@code browse()} / {@code sendMessage()} stringify bodies;
 *       faithful message I/O needs the Core client.
 * </ul>
 */
public record BrokerCapabilities(
        CapabilityAssessment managementRead,
        CapabilityAssessment managementWrite,
        CapabilityAssessment notifications,
        CapabilityAssessment messageIo) {

    public enum CapabilityStatus {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN
    }

    /**
     * @param status what Phase 1 could determine
     * @param reason human-readable, always present
     * @param brokerXmlSnippet the {@code broker.xml} fragment that would close the
     *     gap, or {@code null} when there is nothing to paste
     */
    public record CapabilityAssessment(CapabilityStatus status, String reason, String brokerXmlSnippet) {

        public static CapabilityAssessment available(String reason) {
            return new CapabilityAssessment(CapabilityStatus.AVAILABLE, reason, null);
        }

        public static CapabilityAssessment unavailable(String reason) {
            return new CapabilityAssessment(CapabilityStatus.UNAVAILABLE, reason, null);
        }

        public static CapabilityAssessment unavailable(String reason, String brokerXmlSnippet) {
            return new CapabilityAssessment(CapabilityStatus.UNAVAILABLE, reason, brokerXmlSnippet);
        }

        public static CapabilityAssessment unknown(String reason, String brokerXmlSnippet) {
            return new CapabilityAssessment(CapabilityStatus.UNKNOWN, reason, brokerXmlSnippet);
        }
    }
}
