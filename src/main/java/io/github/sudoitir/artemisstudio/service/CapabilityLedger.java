package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.broker.BrokerXmlSnippets;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What Studio has actually observed about a cluster's management-write authority
 * (ADR-0049 D5).
 *
 * <p>{@code CapabilityProbe} makes no write and must not — probing a connection by
 * creating something on the broker would be a side effect nobody asked for. So the
 * probe cannot answer this question on its own, and the answer has to be
 * remembered from the writes the product performs anyway. This is that memory.
 *
 * <p>Three states, and the honest one is the default:
 *
 * <ul>
 *   <li>no record — <b>unknown</b>. No write has been attempted. The UI offers
 *       write operations and says the first one will settle it. Absence of
 *       evidence must not block the operator.
 *   <li><b>available</b> — a management write succeeded.
 *   <li><b>unavailable</b> — a management write was refused for an
 *       <em>authorization</em> reason, with the {@code broker.xml} that would grant
 *       it.
 * </ul>
 *
 * <p>Only an authorization refusal may record unavailability. An unreachable
 * broker or a rejected argument says nothing about the connection's authority, and
 * treating it as though it did would let one malformed request present a working
 * connection as unable to write, permanently.
 */
@Service
@RequiredArgsConstructor
public class CapabilityLedger {

    private final ClusterRepository clusters;

    /**
     * The recorded assessment, or empty when no write has been attempted. Empty is
     * the caller's cue to report unknown — never to guess.
     */
    @Transactional(readOnly = true)
    public Optional<CapabilityAssessment> managementWrite(UUID clusterId) {
        return clusters.findById(clusterId).map(CapabilityLedger::toAssessment).filter(java.util.Objects::nonNull);
    }

    /**
     * Record a successful management write.
     *
     * <p>Runs in its own transaction: the evidence is true regardless of what the
     * surrounding command does next. A fan-out that writes successfully on one node
     * and then fails the whole command on the cap check has still proved the
     * connection can write, and rolling that observation back with the command would
     * throw away the only evidence the probe will ever get.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWriteSucceeded(UUID clusterId) {
        clusters.findById(clusterId).ifPresent(cluster -> {
            if (!"AVAILABLE".equals(cluster.getManagementWriteStatus())) {
                cluster.recordManagementWriteSucceeded("A management write on this connection succeeded.");
                clusters.save(cluster);
            }
        });
    }

    /** Record an authorization refusal. Callers must not route any other failure here. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWriteRefused(UUID clusterId, String reason) {
        clusters.findById(clusterId).ifPresent(cluster -> {
            cluster.recordManagementWriteRefused("The broker refused a management write for these credentials: "
                    + (reason == null ? "no reason given" : reason));
            clusters.save(cluster);
        });
    }

    private static CapabilityAssessment toAssessment(ClusterEntity cluster) {
        String status = cluster.getManagementWriteStatus();
        if (status == null) {
            return null;
        }
        String observed = " Observed " + cluster.getManagementWriteObservedAt() + ".";
        if ("AVAILABLE".equals(status)) {
            return CapabilityAssessment.available(cluster.getManagementWriteReason() + observed);
        }
        return CapabilityAssessment.unavailable(
                cluster.getManagementWriteReason() + observed, BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING);
    }
}
