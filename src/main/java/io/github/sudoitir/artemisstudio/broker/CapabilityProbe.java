package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.broker.core.SubscriptionVerdict;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Determines a broker connection's {@link BrokerCapabilities} over Jolokia,
 * without ever mutating the broker.
 *
 * <p>No operation this class invokes mutates anything; no queue, address, or
 * message is ever created.
 *
 * <p>{@code MANAGEMENT_WRITE} is therefore <em>not decided here</em> (ADR-0049
 * D5). It used to be inferred from a read-only {@code listNetworkTopology()} exec
 * succeeding, which proves only that Jolokia is not under a read-only policy — a
 * {@code jolokia-access.xml} can still whitelist per operation, and the broker's
 * own {@code manage} permission is a separate gate. Nothing depended on that
 * inference until queue lifecycle did, and a create button resting on a guess is
 * the capability model lying to the operator (non-negotiable #5).
 *
 * <p>So the probe returns {@code UNKNOWN} for it, and the caller overlays whatever
 * an actual write has established. Honest absence of evidence, not a guess.
 */
@Component
public class CapabilityProbe {

    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";

    public BrokerCapabilities probe(JolokiaBrokerClient client, SubscriptionVerdict notificationVerdict) {
        return probe(client, notificationVerdict, Optional.empty());
    }

    /**
     * @param recordedWrite what an actual management write has established for this
     *     connection, if one has ever been attempted (ADR-0049 D5). Empty means no
     *     write has been tried, and the answer is honestly unknown. Supplied by
     *     {@code CapabilityLedger}; the probe never writes to find out for itself.
     */
    public BrokerCapabilities probe(
            JolokiaBrokerClient client,
            SubscriptionVerdict notificationVerdict,
            Optional<CapabilityAssessment> recordedWrite) {
        CapabilityAssessment read = probeManagementRead(client);
        if (read.status() != BrokerCapabilities.CapabilityStatus.AVAILABLE) {
            // No read means nothing else can be judged; report the rest as unknown.
            CapabilityAssessment unknown = CapabilityAssessment.unknown(
                    "Not assessed — management reads are not available on this connection.", null);
            return new BrokerCapabilities(read, unknown, unknown, unknown, unknown);
        }

        CapabilityAssessment write = recordedWrite.orElseGet(CapabilityProbe::probeManagementWrite);
        CapabilityAssessment notifications = assessNotifications(client, notificationVerdict);
        CapabilityAssessment messageIo = assessMessageIo(write);
        CapabilityAssessment slowConsumers = assessSlowConsumerDetection(client);
        return new BrokerCapabilities(read, write, notifications, messageIo, slowConsumers);
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

    /**
     * Always {@code UNKNOWN}. Establishing management-write authority needs an
     * actual write, and probing is not allowed to make one. The recorded evidence
     * from real writes is overlaid by {@code CapabilityLedger}; when there is none,
     * this is the answer the operator sees, and it is the true one.
     */
    private static CapabilityAssessment probeManagementWrite() {
        return CapabilityAssessment.unknown(
                "No management write has been attempted on this connection yet, so Studio cannot say"
                        + " whether it can perform one. A read succeeding does not establish it: Jolokia can"
                        + " whitelist individual operations and the broker's 'manage' permission is a separate"
                        + " gate. The next write operation will settle it.",
                BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING);
    }

    /**
     * NOTIFICATIONS is the outcome of the cluster's Core subscription (ADR-0026,
     * D5), read from a cached {@link SubscriptionVerdict} — this method opens no
     * connection. The Jolokia-visible preconditions are still reported in the
     * reason text.
     */
    private CapabilityAssessment assessNotifications(JolokiaBrokerClient client, SubscriptionVerdict verdict) {
        boolean coreAcceptor = hasCoreAcceptor(client);
        boolean notificationsAddress = hasNotificationsAddress(client);
        String preconditions = "Preconditions visible over Jolokia: CORE acceptor "
                + (coreAcceptor ? "present" : "not found") + "; " + NOTIFICATIONS_ADDRESS + " address "
                + (notificationsAddress ? "present" : "not found") + ".";

        return switch (verdict) {
            case SubscriptionVerdict.Connected connected ->
                CapabilityAssessment.available(
                        "Subscribed to " + NOTIFICATIONS_ADDRESS + " on " + connected.nodeCount()
                                + " node(s) since " + connected.since()
                                + ". Connection, session, delivered and expired events additionally require"
                                + " NotificationActiveMQServerPlugin; Studio cannot tell a broker without the"
                                + " plugin from an idle one, so the snippet is shown either way. " + preconditions,
                        BrokerXmlSnippets.NOTIFICATION_PLUGIN);
            case SubscriptionVerdict.Failed failed -> assessFailedSubscription(failed, preconditions);
            case SubscriptionVerdict.NotAttempted ignored ->
                CapabilityAssessment.unknown(
                        "No live node has been probed yet — the first scrape cycle has not completed. " + preconditions,
                        null);
        };
    }

    private CapabilityAssessment assessFailedSubscription(SubscriptionVerdict.Failed failed, String preconditions) {
        return switch (failed.kind()) {
            case PERMISSION_DENIED ->
                CapabilityAssessment.unavailable(
                        "The broker refused the subscription: " + failed.reason()
                                + ". A Core subscriber needs both consume AND createNonDurableQueue on "
                                + NOTIFICATIONS_ADDRESS + "; Artemis matches the single most-specific"
                                + " security-setting, so that block must restate every permission. " + preconditions,
                        BrokerXmlSnippets.NOTIFICATIONS_SECURITY_SETTING);
            case NO_CORE_URL, UNREACHABLE ->
                CapabilityAssessment.unavailable(
                        "No reachable Core URL for a live node. Discovery stores the broker-advertised"
                                + " connector, which is often unresolvable from where Studio runs; set a manual"
                                + " Core URL on the node. " + preconditions,
                        BrokerXmlSnippets.CORE_ACCEPTOR);
            case UNAUTHORIZED ->
                CapabilityAssessment.unavailable(
                        "The broker rejected the Core credentials for the notification subscription. " + preconditions,
                        null);
            case TLS_FAILED ->
                CapabilityAssessment.unavailable(
                        "TLS to the broker's Core acceptor failed: " + failed.reason() + ". " + preconditions, null);
            case UNKNOWN ->
                CapabilityAssessment.unavailable(
                        "The Core subscription failed: " + failed.reason() + ". " + preconditions, null);
        };
    }

    /**
     * Whether the broker runs its own slow-consumer detection (ADR-0044). The
     * slice-0 spike against Artemis 2.44 confirmed {@code getAddressSettingsAsJSON}
     * returns 18 fields, of which the only slow-consumer one is
     * {@code slowConsumerThresholdMeasurementUnit} — the threshold, check period and
     * policy are not exposed. So the honest answer is normally UNKNOWN: Studio cannot
     * tell a configured threshold from an absent one, and saying "off" would be a
     * guess. The branches for a broker version that does expose it are here so that
     * the day it does, the answer improves without a code change elsewhere.
     */
    private CapabilityAssessment assessSlowConsumerDetection(JolokiaBrokerClient client) {
        try {
            JsonNode settings = client.execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", "#");
            JsonNode threshold = settings == null ? null : settings.get("slowConsumerThreshold");
            if (threshold == null || threshold.isNull()) {
                return CapabilityAssessment.unknown(
                        "This broker's management surface does not expose slow-consumer-threshold"
                                + " (only slowConsumerThresholdMeasurementUnit), so Studio cannot tell whether"
                                + " native detection is configured. Studio's own ackRatePerConsumer alert rule"
                                + " works either way, but resolves to a queue on a node, never to one consumer.",
                        BrokerXmlSnippets.forSlowConsumerDetection());
            }
            long value = threshold.asLong(-1L);
            if (value <= 0) {
                return CapabilityAssessment.unavailable(
                        "Native slow-consumer detection is disabled (slow-consumer-threshold " + value + ").",
                        BrokerXmlSnippets.forSlowConsumerDetection());
            }
            JsonNode unit = settings.get("slowConsumerThresholdMeasurementUnit");
            JsonNode policy = settings.get("slowConsumerPolicy");
            return CapabilityAssessment.available("Native slow-consumer detection is on: threshold " + value
                    + (unit == null || unit.isNull() ? "" : " " + unit.asString())
                    + (policy == null || policy.isNull() ? "" : ", policy " + policy.asString())
                    + ". The broker's own CONSUMER_SLOW notification is authoritative and names the consumer.");
        } catch (BrokerConnectionException e) {
            return CapabilityAssessment.unknown(
                    "Could not read address settings to assess slow-consumer detection: " + e.getMessage(),
                    BrokerXmlSnippets.forSlowConsumerDetection());
        }
    }

    /**
     * Message I/O rides on the same management-write authority, so it inherits that
     * verdict — including its uncertainty. It must not be reported as unavailable
     * just because no write has been attempted yet: that would hide working buttons
     * behind an absence of evidence, which is the opposite of what D5 is for.
     */
    private CapabilityAssessment assessMessageIo(CapabilityAssessment write) {
        String what = "Through Jolokia: browse, send, move/retry/delete/expire and purge. Bodies are"
                + " carried as text and the broker truncates oversized body/property values (disclosed"
                + " per message); faithful binary message I/O needs the Core client.";
        return switch (write.status()) {
            case AVAILABLE -> CapabilityAssessment.available("Available. " + what);
            case UNKNOWN ->
                CapabilityAssessment.unknown(
                        "Offered, but not yet established — it needs the same management-write authority,"
                                + " which no write has tested yet. " + what,
                        BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING);
            case UNAVAILABLE ->
                CapabilityAssessment.unavailable(
                        "Needs management-write access, which this connection has been refused.",
                        BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING);
        };
    }

    private boolean hasCoreAcceptor(JolokiaBrokerClient client) {
        List<String> acceptors = client.search(BrokerMBeans.acceptorsPattern(client.resolveBrokerObjectName()));
        for (String acceptor : acceptors) {
            try {
                JolokiaResponse response = client.single(JolokiaRequest.read(acceptor, "Parameters"));
                JsonNode parameters = response.ok() ? response.attribute("Parameters") : null;
                if (parameters == null) {
                    continue;
                }
                JsonNode protocols = parameters.get("protocols");
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
