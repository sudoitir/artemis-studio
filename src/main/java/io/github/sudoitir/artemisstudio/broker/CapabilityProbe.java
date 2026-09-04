package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Determines a broker connection's {@link BrokerCapabilities} over Jolokia,
 * without ever mutating the broker.
 *
 * <p>The only operation this class invokes is {@code listNetworkTopology()},
 * which is read-only. {@code MANAGEMENT_WRITE} is <em>inferred</em> from it
 * succeeding (ADR-0002); no queue, address, or message is ever created.
 */
@Component
public class CapabilityProbe {

    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";

    public BrokerCapabilities probe(JolokiaBrokerClient client) {
        CapabilityAssessment read = probeManagementRead(client);
        if (read.status() != BrokerCapabilities.CapabilityStatus.AVAILABLE) {
            // No read means nothing else can be judged; report the rest as unknown.
            CapabilityAssessment unknown = CapabilityAssessment.unknown(
                    "Not assessed — management reads are not available on this connection.", null);
            return new BrokerCapabilities(read, unknown, unknown, unknown);
        }

        CapabilityAssessment write = probeManagementWrite(client);
        CapabilityAssessment notifications = assessNotifications(client);
        CapabilityAssessment messageIo = assessMessageIo(write);
        return new BrokerCapabilities(read, write, notifications, messageIo);
    }

    private CapabilityAssessment probeManagementRead(JolokiaBrokerClient client) {
        try {
            JolokiaResponse response = client.readBrokerAttributes("Version");
            if (response.ok()) {
                return CapabilityAssessment.available("A broker attribute read returned 200.");
            }
            return CapabilityAssessment.unavailable("The broker MBean read failed: "
                    + (response.error() != null ? response.error() : "status " + response.status()));
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.UNAUTHORIZED) {
                return CapabilityAssessment.unavailable("The broker rejected these credentials for a management read.");
            }
            throw e;
        }
    }

    private CapabilityAssessment probeManagementWrite(JolokiaBrokerClient client) {
        String caveat = " Inferred from a read-only listNetworkTopology() call succeeding — a"
                + " jolokia-access.xml can still whitelist individual operations, so a specific"
                + " command may yet be refused.";
        try {
            JolokiaResponse response = client.execOnBroker("listNetworkTopology()");
            if (response.ok()) {
                return CapabilityAssessment.available(
                        "Jolokia is not under a read-only policy and the user cleared 'manage'." + caveat);
            }
            return CapabilityAssessment.unavailable("listNetworkTopology() was refused (" + response.status()
                    + "). Jolokia may be under a read-only policy, or the user lacks 'manage'.");
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.UNAUTHORIZED) {
                return CapabilityAssessment.unavailable(
                        "The broker refused a management operation for these credentials.");
            }
            throw e;
        }
    }

    private CapabilityAssessment assessNotifications(JolokiaBrokerClient client) {
        boolean coreAcceptor = hasCoreAcceptor(client);
        boolean notificationsAddress = hasNotificationsAddress(client);

        String preconditions = "Preconditions visible over Jolokia: CORE acceptor "
                + (coreAcceptor ? "present" : "not found") + "; " + NOTIFICATIONS_ADDRESS + " address "
                + (notificationsAddress ? "present" : "not found") + ".";

        return CapabilityAssessment.unknown(
                "Live events need the Core protocol client (a later release) plus two broker.xml changes;"
                        + " Studio cannot confirm them over Jolokia. " + preconditions,
                BrokerXmlSnippets.forNotifications());
    }

    private CapabilityAssessment assessMessageIo(CapabilityAssessment write) {
        if (write.status() == BrokerCapabilities.CapabilityStatus.AVAILABLE) {
            return CapabilityAssessment.available(
                    "Available through Jolokia: browse, send, move/retry/delete/expire and purge all work."
                            + " Bodies are carried as text and the broker truncates oversized body/property"
                            + " values (disclosed per message); faithful binary message I/O is Phase 4 (Core client).");
        }
        return CapabilityAssessment.unavailable("Needs management-write access, which this connection does not have.");
    }

    private boolean hasCoreAcceptor(JolokiaBrokerClient client) {
        List<String> acceptors = client.search(BrokerMBeans.acceptorsPattern(client.resolveBrokerObjectName()));
        for (String acceptor : acceptors) {
            try {
                JolokiaResponse response = client.single(JolokiaRequest.read(acceptor, "Parameters"));
                if (!response.ok() || response.value() == null) {
                    continue;
                }
                JsonNode protocols = response.value().get("protocols");
                // No 'protocols' parameter means the acceptor carries every protocol,
                // which includes CORE; an explicit list must name CORE.
                if (protocols == null || protocols.isNull()) {
                    return true;
                }
                if (protocols.asText().toUpperCase().contains("CORE")) {
                    return true;
                }
            } catch (BrokerConnectionException e) {
                // A single unreadable acceptor is not decisive; try the next.
            }
        }
        return false;
    }

    private boolean hasNotificationsAddress(JolokiaBrokerClient client) {
        List<String> addresses = client.search(BrokerMBeans.addressesPattern(client.resolveBrokerObjectName()));
        String needle = "address=\"" + NOTIFICATIONS_ADDRESS + "\"";
        return addresses.stream().anyMatch(name -> name.contains(needle));
    }
}
