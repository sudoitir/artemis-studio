package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertRow;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs and removes one capture tap on one node (ADR-0062 D1).
 *
 * <p>A tap is four broker objects, created in this order because each depends on the
 * one before it:
 *
 * <ol>
 *   <li><b>address settings</b> on {@code artemis-studio.capture.#} —
 *       {@code addressFullMessagePolicy=DROP} so the broker never blocks or pages on
 *       Studio's account, and {@code expiryDelay} with
 *       {@code autoCreateExpiryResources=false} so an abandoned tap is bounded in age
 *       as well as in size. Artemis' own documentation warns against paging an address
 *       that holds ring queues, which is why this is part of the tap rather than an
 *       optional extra;
 *   <li><b>security settings</b> on the same match, restricting the capture queue to
 *       Studio's own broker role. The capture queue is a complete second copy of
 *       production payload, and creating it unguarded would hand a tap on production
 *       traffic to every client the broker authorises;
 *   <li><b>the capture queue</b> — non-durable, anycast, {@code ring-size} bounded, so
 *       the worst case with Studio dead forever is a fixed number of messages held in
 *       memory;
 *   <li><b>the divert</b> — non-exclusive, so production routing is untouched.
 * </ol>
 *
 * <p>Removal is the reverse, and it is always an explicit act: none of these objects
 * disappears on a broker restart (ADR-0065). Both directions are idempotent, because
 * the reconciler is the only caller and it runs on a schedule.
 *
 * <p><b>Measured, not assumed.</b> Every management signature used here was checked
 * against the running broker rather than the classpath: {@code addSecuritySettings}'
 * single-JSON overload exists on the {@code ActiveMQServerControl} interface but is
 * <em>not</em> registered on the MBean, which answers with {@code IllegalArgumentException}
 * and lists the positional arms. The 13-string arm below is the one the broker accepts,
 * and its argument order — send, consume, createDurableQueue, deleteDurableQueue,
 * createNonDurableQueue, deleteNonDurableQueue, manage, browse, createAddress,
 * deleteAddress, view, edit — was confirmed by writing it and reading it back through
 * {@code getRolesAsJSON}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureTap {

    private static final String ADD_ADDRESS_SETTINGS = "addAddressSettings(java.lang.String,java.lang.String)";

    private static final String ADD_SECURITY_SETTINGS = "addSecuritySettings(java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)";

    private final DivertOperations divertOps;
    private final QueueLifecycleOperations queueOps;
    private final ArtemisStudioProperties properties;
    private final ObjectMapper mapper;

    /** What one tap covers: a source address on a node, for a subscription. */
    public record Spec(UUID subscriptionId, String address, long ringSize, String filter) {}

    // ---- install ---------------------------------------------------------

    /**
     * Install, or confirm already installed. Returns the capture queue's name, which
     * is also its address and its divert's name.
     *
     * @throws CaptureRefusedException when the broker's configuration means capture
     *     would either see nothing or leave payload unguarded — both of which are worse
     *     than not capturing at all.
     */
    public String install(JolokiaBrokerClient client, String instanceId, Spec spec) {
        String broker = client.resolveBrokerObjectName();
        refuseIfShadowedByExclusiveDivert(client, spec.address());

        String name = CaptureNames.of(instanceId, spec.address(), spec.subscriptionId());
        applyAddressSettings(client, broker, spec.ringSize());
        applySecuritySettings(client, broker);

        String queue = CaptureNames.queueOf(name);
        tolerateAlready(() -> queueOps.createAddress(client, broker, queue, "ANYCAST"));
        tolerateAlready(() -> queueOps.createQueue(client, broker, queueConfig(queue, spec.ringSize())));
        tolerateAlready(() -> divertOps.createDivert(
                client,
                broker,
                DivertOperations.divertConfig(
                        name, routingNameFor(name), spec.address(), queue, false, spec.filter(), "ANYCAST")));

        // Verified, not assumed. Artemis answers 200 and logs a WARN when it declines
        // to deploy a divert — a routing-name that collides with an existing binding is
        // the way to get there, and it was exactly how this went wrong the first time.
        // An install that reports success without a divert would capture nothing while
        // the console said it was capturing, which is the one outcome D6 exists to stop.
        if (!installedNames(client, instanceId).contains(name)) {
            throw new CaptureRefusedException(
                    "The broker accepted the request but did not deploy the capture divert for " + spec.address()
                            + ". Its own log says why — the usual cause is a binding that already exists under the"
                            + " same routing name. Nothing is capturing on this node.",
                    null);
        }
        return name;
    }

    /**
     * Remove a tap, and the shared settings with it once this instance has no tap left
     * on the node. Idempotent: anything already gone is a success.
     */
    public void remove(JolokiaBrokerClient client, String instanceId, String name) {
        String broker = client.resolveBrokerObjectName();
        String queue = CaptureNames.queueOf(name);
        tolerateAlready(() -> divertOps.destroyDivert(client, broker, name));
        tolerateAlready(() -> queueOps.destroyQueue(client, broker, queue));
        tolerateAlready(() -> queueOps.deleteAddress(client, broker, queue));
        if (installedNames(client, instanceId).isEmpty()) {
            client.single(JolokiaRequest.exec(broker, "removeAddressSettings(java.lang.String)", CaptureNames.MATCH));
            client.single(JolokiaRequest.exec(broker, "removeSecuritySettings(java.lang.String)", CaptureNames.MATCH));
        }
    }

    /**
     * The divert's routing name, which must not collide with any existing binding.
     *
     * <p>Kept distinct from both the divert's name and the capture queue's, because a
     * routing name is itself a binding and a collision is refused the same silent way
     * a name collision is.
     */
    static String routingNameFor(String name) {
        return name + ".routing";
    }

    /** The capture diverts this instance owns on this node — the reconciler's actual state. */
    public List<String> installedNames(JolokiaBrokerClient client, String instanceId) {
        return divertOps.listDiverts(client, null, null).stream()
                .map(DivertRow::uniqueName)
                .filter(n -> CaptureNames.ownedBy(n, instanceId))
                .toList();
    }

    // ---- preflight -------------------------------------------------------

    /**
     * Artemis evaluates exclusive diverts before non-exclusive ones, so a capture
     * divert behind one never sees the traffic. It would install cleanly and record
     * nothing — the worst available outcome, because an empty index reads as a quiet
     * queue rather than as a defeated tap.
     */
    private void refuseIfShadowedByExclusiveDivert(JolokiaBrokerClient client, String address) {
        List<DivertRow> shadowing = divertOps.listDiverts(client, null, null).stream()
                .filter(DivertRow::exclusive)
                .filter(d -> address.equals(d.address()))
                .toList();
        if (shadowing.isEmpty()) {
            return;
        }
        DivertRow first = shadowing.get(0);
        throw new CaptureRefusedException(
                "The divert '" + first.uniqueName() + "' on " + address + " is exclusive, and Artemis applies "
                        + "exclusive diverts before every other one, so a capture divert here would never see a "
                        + "message. Capture " + first.forwardingAddress() + " instead — that is where this address's "
                        + "traffic actually goes.",
                null);
    }

    // ---- the four objects ------------------------------------------------

    private void applyAddressSettings(JolokiaBrokerClient client, String broker, long ringSize) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("addressFullMessagePolicy", "DROP");
        settings.put("defaultRingSize", ringSize);
        settings.put("expiryDelay", properties.capture().expiry().toMillis());
        settings.put("autoCreateExpiryResources", false);
        // Auto-create the capture *address*, and nothing else. This is a safety
        // property, not a convenience: the divert lives in the bindings journal and
        // therefore replicates to a backup, while the non-durable capture queue does
        // not — so a promoted backup can hold a divert whose forwarding address does
        // not exist yet, and Artemis rejects the *producer's* send with
        // AMQ229203 when that happens. Measured on 2.56.0, by failing a pair over with
        // this set to false. With it true the copy is routed to an address with no
        // queue and dropped, capture reports the loss, and production is untouched —
        // which is the only acceptable ordering (non-negotiable #1).
        settings.put("autoCreateAddresses", true);
        settings.put("autoDeleteAddresses", true);
        settings.put("autoCreateQueues", false);
        settings.put("autoDeleteQueues", false);
        JolokiaResponse res = client.single(JolokiaRequest.exec(
                broker, ADD_ADDRESS_SETTINGS, CaptureNames.MATCH, mapper.writeValueAsString(settings)));
        if (!res.ok()) {
            throw new CaptureRefusedException(
                    "This broker would not accept the address settings that bound the capture queue: "
                            + res.error()
                            + ". Without them an abandoned capture queue could page production payload to disk, so "
                            + "capture is refused rather than installed unbounded.",
                    captureBrokerXml(ringSize));
        }
    }

    /**
     * Consume, browse and the non-durable queue lifecycle, granted to Studio's own
     * role and to nothing else. Send is deliberately not granted: a divert routes
     * inside the server and is not a client send, so granting it would only widen who
     * can write into the capture address.
     */
    private void applySecuritySettings(JolokiaBrokerClient client, String broker) {
        String role = properties.capture().brokerRole();
        JolokiaResponse res = client.single(JolokiaRequest.exec(
                broker,
                ADD_SECURITY_SETTINGS,
                CaptureNames.MATCH,
                "", // send
                role, // consume
                "", // createDurableQueue
                "", // deleteDurableQueue
                role, // createNonDurableQueue
                role, // deleteNonDurableQueue
                "", // manage
                role, // browse
                "", // createAddress
                "", // deleteAddress
                "", // view
                "")); // edit
        if (!res.ok()) {
            throw new CaptureRefusedException(
                    "This broker would not let Studio restrict the capture queue: " + res.error()
                            + ". The capture queue is a complete second copy of this address's payload, so it is not "
                            + "created at all rather than created readable by every client the broker authorises.",
                    captureBrokerXml(0));
        }
    }

    /**
     * Non-durable so the queue is never written to the journal, anycast because
     * address and queue coincide for a capture queue, and ring-bounded so the broker
     * drops the oldest message rather than growing.
     */
    private static Map<String, Object> queueConfig(String name, long ringSize) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("address", name);
        config.put("routing-type", "ANYCAST");
        config.put("durable", false);
        config.put("ring-size", ringSize);
        config.put("max-consumers", 1);
        config.put("purge-on-no-consumers", false);
        config.put("auto-delete", false);
        return config;
    }

    /** The configuration an operator would add to make a refusal go away. */
    public String captureBrokerXml(long ringSize) {
        return """
                <security-settings>
                  <security-setting match="%1$s">
                    <permission type="consume" roles="%2$s"/>
                    <permission type="browse" roles="%2$s"/>
                    <permission type="createNonDurableQueue" roles="%2$s"/>
                    <permission type="deleteNonDurableQueue" roles="%2$s"/>
                  </security-setting>
                </security-settings>

                <address-settings>
                  <address-setting match="%1$s">
                    <address-full-policy>DROP</address-full-policy>
                    <default-ring-size>%3$d</default-ring-size>
                    <expiry-delay>%4$d</expiry-delay>
                    <auto-create-expiry-resources>false</auto-create-expiry-resources>
                  </address-setting>
                </address-settings>
                """.formatted(
                        CaptureNames.MATCH,
                        properties.capture().brokerRole(),
                        ringSize,
                        properties.capture().expiry().toMillis());
    }

    /** An object that is already in the requested state is a success, in both directions. */
    private static void tolerateAlready(Runnable operation) {
        try {
            operation.run();
        } catch (ManagementRefusal e) {
            if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                throw e;
            }
        }
    }
}
