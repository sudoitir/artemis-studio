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

    /**
     * In {@code messageIo}'s reason exactly when the broker is still truncating
     * management-returned bodies. A separate fact from the capability's status,
     * which is about authority: a connection that can read and write messages
     * perfectly well still truncates them until the cap is lifted. Shared so the
     * recommendation that lifts it (ADR-0068) tests for the same string the probe
     * writes, and stops recommending it once it is gone.
     */
    public static final String TRUNCATING =
            "truncates oversized body/property values at" + " management-message-attribute-size-limit";

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
        // One read of the catch-all address setting answers two questions; asking
        // twice would double the cost of every capability probe for nothing.
        JsonNode catchAll = catchAllSettings(client);
        CapabilityAssessment messageIo = assessMessageIo(write, catchAll);
        CapabilityAssessment slowConsumers = assessSlowConsumerDetection(catchAll);
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
     * Whether the broker runs its own slow-consumer detection (ADR-0044).
     *
     * <p>The slice-0 spike read this as "the threshold is never exposed", and the
     * probe reported UNKNOWN forever on that basis. Measuring it directly against
     * 2.44.0 ({@code docs/broker-management-notes.md} §16 M8) showed the opposite:
     * {@code getAddressSettingsAsJSON} <em>does</em> echo {@code slowConsumerThreshold}
     * once one is set. The spike had only ever looked at a broker that had none, and
     * §15 M1 is the rest of the explanation — a key nobody set is simply absent from
     * the answer, not reported as a default.
     *
     * <p>So an absent threshold now means "none is configured", which is a fact the
     * operator can act on, rather than "Studio cannot tell", which left a permanent
     * unanswerable row in the ledger. A broker old enough not to echo it at all would
     * be reported as off when it is on; that is the one case this trades away, and
     * the reason text names it.
     */
    private CapabilityAssessment assessSlowConsumerDetection(JsonNode settings) {
        if (settings == UNREADABLE) {
            return CapabilityAssessment.unknown(
                    "Could not read the catch-all address setting to assess slow-consumer detection.",
                    BrokerXmlSnippets.forSlowConsumerDetection());
        }
        {
            JsonNode threshold = settings == null ? null : settings.get("slowConsumerThreshold");
            if (threshold == null || threshold.isNull()) {
                return CapabilityAssessment.unavailable(
                        "No slow-consumer-threshold is set on the catch-all address setting, so the broker"
                                + " does its own detection on nothing. A broker that has one reports it here"
                                + " (measured on 2.44.0); a much older one may not echo it at all, in which case"
                                + " it is configured and this row is wrong. Studio's own ackRatePerConsumer alert"
                                + " rule works either way, but resolves to a queue on a node, never to one"
                                + " consumer.",
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
        }
    }

    /** A sentinel for "the read failed", distinct from "the broker returned nothing". */
    private static final JsonNode UNREADABLE = tools.jackson.databind.node.MissingNode.getInstance();

    private JsonNode catchAllSettings(JolokiaBrokerClient client) {
        try {
            return client.execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", "#");
        } catch (BrokerConnectionException e) {
            return UNREADABLE;
        }
    }

    /**
     * Message I/O rides on the same management-write authority, so it inherits that
     * verdict — including its uncertainty. It must not be reported as unavailable
     * just because no write has been attempted yet: that would hide working buttons
     * behind an absence of evidence, which is the opposite of what D5 is for.
     */
    private CapabilityAssessment assessMessageIo(CapabilityAssessment write, JsonNode settings) {
        boolean capped = truncationCapped(settings);
        String what = "Through Jolokia: browse, send, move/retry/delete/expire and purge. Bodies are"
                + " carried as text"
                + (capped
                        ? " and the broker " + TRUNCATING + " (disclosed per message)"
                        : " and management-message-attribute-size-limit is -1, so whole bodies come back")
                + "; faithful binary message I/O needs the Core client.";
        return switch (write.status()) {
            case AVAILABLE ->
                capped
                        ? CapabilityAssessment.available(
                                "Available, with bodies truncated. " + what,
                                BrokerXmlSnippets.MESSAGE_ATTRIBUTE_SIZE_LIMIT)
                        : CapabilityAssessment.available("Available. " + what);
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

    /**
     * Whether the broker still truncates management-returned bodies. The key reads
     * back once it is set — the apply engine verifies it that way — so an absent
     * value means the default cap is in force, not that the answer is unknowable.
     */
    private static boolean truncationCapped(JsonNode settings) {
        if (settings == null || settings == UNREADABLE) {
            return true;
        }
        JsonNode limit = settings.get("managementMessageAttributeSizeLimit");
        return limit == null || limit.isNull() || limit.asLong(0L) >= 0;
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
