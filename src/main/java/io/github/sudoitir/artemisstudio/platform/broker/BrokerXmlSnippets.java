package io.github.sudoitir.artemisstudio.platform.broker;

/**
 * The {@code broker.xml} fragments an operator must add for live notifications
 * (Phase 4). Both were proven necessary against the dev broker pair in Phase 0
 * and are reproduced here verbatim from the dev {@code broker.xml} files under
 * {@code deploy/compose/artemis/}.
 */
public final class BrokerXmlSnippets {

    private BrokerXmlSnippets() {}

    /**
     * Artemis matches the <em>single most-specific</em> {@code security-setting},
     * so this block must restate every permission a Core/JMS subscriber needs on
     * {@code activemq.notifications} — it does not inherit from {@code match="#"}.
     * {@code consume} alone yields {@code AMQ229213 ... CREATE_NON_DURABLE_QUEUE}.
     */
    public static final String NOTIFICATIONS_SECURITY_SETTING = """
            <security-setting match="activemq.notifications">
              <permission type="consume" roles="amq"/>
              <permission type="createNonDurableQueue" roles="amq"/>
              <permission type="deleteNonDurableQueue" roles="amq"/>
            </security-setting>
            """;

    /**
     * Without this plugin the connection, session, delivered and expired
     * notification classes are never emitted at all — no permission change makes
     * them appear.
     */
    public static final String NOTIFICATION_PLUGIN = """
            <broker-plugins>
              <broker-plugin class-name="org.apache.activemq.artemis.core.server.plugin.impl.NotificationActiveMQServerPlugin">
                <property key="SEND_CONNECTION_NOTIFICATIONS" value="true"/>
                <property key="SEND_SESSION_NOTIFICATIONS" value="true"/>
                <property key="SEND_DELIVERED_NOTIFICATIONS" value="true"/>
                <property key="SEND_EXPIRED_NOTIFICATIONS" value="true"/>
              </broker-plugin>
            </broker-plugins>
            """;

    /** Both snippets, in the order they appear in {@code broker.xml}. */
    /**
     * The {@code <divert>} that would make a broker's configuration carry a divert
     * Studio created over management. Built from the operator's own entered values so
     * it can be pasted rather than retyped.
     *
     * <p>This is not a warning that the divert is temporary — it is not (ADR-0065).
     * It is what closes the gap between a broker that is diverting and a broker
     * configuration that says nothing about it.
     */
    public static String forDivert(
            String name,
            String routingName,
            String address,
            String forwardingAddress,
            boolean exclusive,
            String filter,
            String routingType) {
        // Written through StAX so a name, address or filter carrying <, & or a quote stays well-formed.
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            javax.xml.stream.XMLStreamWriter w =
                    javax.xml.stream.XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            w.writeStartElement("diverts");
            w.writeCharacters("\n  ");
            w.writeStartElement("divert");
            w.writeAttribute("name", name);
            element(w, "routing-name", routingName == null || routingName.isBlank() ? name : routingName);
            element(w, "address", address);
            element(w, "forwarding-address", forwardingAddress);
            if (filter != null && !filter.isBlank()) {
                w.writeCharacters("\n    ");
                w.writeEmptyElement("filter");
                w.writeAttribute("string", filter);
            }
            if (routingType != null && !routingType.isBlank()) {
                element(w, "routing-type", routingType.toUpperCase());
            }
            element(w, "exclusive", String.valueOf(exclusive));
            w.writeCharacters("\n  ");
            w.writeEndElement();
            w.writeCharacters("\n");
            w.writeEndElement();
            w.writeCharacters("\n");
            w.close();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IllegalStateException("Could not write the divert's broker.xml", e);
        }
        return out.toString();
    }

    /**
     * The {@code <bridge>} that would make a broker's configuration carry a bridge
     * Studio declared, for an operator to paste into {@code broker.xml}.
     *
     * <p>{@code credentialRef} names the credential Studio holds in its vault; the
     * secret itself is never written here or anywhere else that can be copied
     * (ADR-0092). Exactly one of {@code staticConnectors} and {@code discoveryGroupName}
     * is written, because the broker accepts only one.
     */
    public static String forBridge(
            String name,
            String queueName,
            String forwardingAddress,
            String filter,
            java.util.List<String> staticConnectors,
            String discoveryGroupName,
            String credentialRef) {
        // Written through StAX so a name, address or filter carrying <, & or a quote stays well-formed.
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            javax.xml.stream.XMLStreamWriter w =
                    javax.xml.stream.XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            w.writeStartElement("bridges");
            w.writeCharacters("\n  ");
            w.writeStartElement("bridge");
            w.writeAttribute("name", name);
            element(w, "queue-name", queueName);
            element(w, "forwarding-address", forwardingAddress);
            if (filter != null && !filter.isBlank()) {
                w.writeCharacters("\n    ");
                w.writeEmptyElement("filter");
                w.writeAttribute("string", filter);
            }
            if (credentialRef != null && !credentialRef.isBlank()) {
                w.writeCharacters("\n    ");
                w.writeComment(" Credential '" + credentialRef
                        + "' is held in Artemis Studio's vault and is not exported. Supply it here. ");
                element(w, "user", "${" + credentialRef + ".user}");
                element(w, "password", "${" + credentialRef + ".password}");
            }
            if (staticConnectors != null && !staticConnectors.isEmpty()) {
                w.writeCharacters("\n    ");
                w.writeStartElement("static-connectors");
                for (String connector : staticConnectors) {
                    w.writeCharacters("\n      ");
                    w.writeStartElement("connector-ref");
                    w.writeCharacters(connector);
                    w.writeEndElement();
                }
                w.writeCharacters("\n    ");
                w.writeEndElement();
            } else if (discoveryGroupName != null && !discoveryGroupName.isBlank()) {
                w.writeCharacters("\n    ");
                w.writeEmptyElement("discovery-group-ref");
                w.writeAttribute("discovery-group-name", discoveryGroupName);
            }
            w.writeCharacters("\n  ");
            w.writeEndElement();
            w.writeCharacters("\n");
            w.writeEndElement();
            w.writeCharacters("\n");
            w.close();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IllegalStateException("Could not write the bridge's broker.xml", e);
        }
        return out.toString();
    }

    private static void element(javax.xml.stream.XMLStreamWriter w, String tag, String text)
            throws javax.xml.stream.XMLStreamException {
        w.writeCharacters("\n    ");
        w.writeStartElement(tag);
        w.writeCharacters(text);
        w.writeEndElement();
    }

    public static String forNotifications() {
        return NOTIFICATION_PLUGIN + "\n" + NOTIFICATIONS_SECURITY_SETTING;
    }

    /**
     * Raises the per-message cap on management-returned body/property data.
     * Artemis truncates anything past {@code management-message-attribute-size-limit}
     * (256 bytes by default) and appends a literal {@code , + N more} marker;
     * {@code -1} disables the cap so {@code browse()} returns the whole body.
     * Shown in the message detail panel next to a truncated body — this is a
     * per-message disclosure, not a capability gate (slice 0 proved the limit is
     * not readable back over Jolokia).
     */
    public static final String MESSAGE_ATTRIBUTE_SIZE_LIMIT = """
            <address-settings>
              <address-setting match="#">
                <management-message-attribute-size-limit>-1</management-message-attribute-size-limit>
              </address-setting>
            </address-settings>
            """;

    public static String forMessageBodyLimit() {
        return MESSAGE_ATTRIBUTE_SIZE_LIMIT;
    }

    /**
     * Native slow-consumer detection (ADR-0044). The broker sees every consumer's
     * own delivery rate, which Studio cannot: {@code listAllConsumersAsJSON} carries
     * no per-consumer acknowledgement counter, so Studio's derived rule resolves to
     * a queue on a node and never to a named consumer. When this is configured, the
     * broker emits {@code CONSUMER_SLOW} on {@code activemq.notifications} carrying
     * {@code _AMQ_ConsumerName}, and that is the authoritative verdict.
     *
     * <p>Shown whenever native detection is not reported as configured. The broker
     * echoes {@code slowConsumerThreshold} once one is set and omits it otherwise
     * (notes §16 M8), so "not configured" is observed rather than assumed.
     */
    public static final String SLOW_CONSUMER_DETECTION = """
            <address-settings>
              <address-setting match="#">
                <!-- messages/second below which a consumer is considered slow; -1 disables -->
                <slow-consumer-threshold>1</slow-consumer-threshold>
                <slow-consumer-threshold-measurement-unit>MESSAGES_PER_SECOND</slow-consumer-threshold-measurement-unit>
                <slow-consumer-check-period>5</slow-consumer-check-period>
                <!-- NOTIFY emits CONSUMER_SLOW; KILL also disconnects the consumer -->
                <slow-consumer-policy>NOTIFY</slow-consumer-policy>
              </address-setting>
            </address-settings>
            """;

    /**
     * The snippet plus the notification plumbing that carries its verdict: without
     * the plugin and the {@code activemq.notifications} permissions, a configured
     * threshold produces a broker-side log line Studio never sees.
     */
    public static String forSlowConsumerDetection() {
        return SLOW_CONSUMER_DETECTION + "\n" + forNotifications();
    }

    /**
     * What a management user needs to perform a management <em>write</em>
     * (ADR-0049 D5) — shown when a write has been refused for an authorization
     * reason.
     *
     * <p>Two separate gates refuse independently, and an operator who fixes only one
     * still cannot write:
     *
     * <ul>
     *   <li>the broker's own security settings on {@code activemq.management}, plus
     *       the {@code manage} permission, which is what the management operations
     *       themselves check;
     *   <li>the console/Jolokia layer in front of them, which refuses with a bare
     *       HTTP 403 before the broker sees the call at all — so the user must also
     *       hold a role the console admits.
     * </ul>
     */
    public static final String MANAGEMENT_SECURITY_SETTING = """
            <!-- 1. The broker-side permission the management operations check. -->
            <security-setting match="activemq.management.#">
              <permission type="createNonDurableQueue" roles="amq"/>
              <permission type="createAddress"         roles="amq"/>
              <permission type="consume"               roles="amq"/>
              <permission type="send"                  roles="amq"/>
              <permission type="manage"                roles="amq"/>
            </security-setting>

            <!-- 2. The console/Jolokia layer refuses with HTTP 403 before the broker
                 is reached, so the management user must hold a role it admits. Grant
                 it in etc/artemis-roles.properties, e.g.:
                   amq = <your-management-user>
            -->
            """;

    /**
     * A CORE-protocol acceptor. Shown when no live node has a reachable Core URL:
     * either the broker exposes no CORE acceptor, or discovery only knows an
     * internal connector hostname and the operator must set a manual Core URL on
     * the node.
     */
    public static final String CORE_ACCEPTOR = """
            <acceptors>
              <acceptor name="artemis">tcp://0.0.0.0:61616?protocols=CORE,AMQP,STOMP,MQTT,OPENWIRE</acceptor>
            </acceptors>
            """;

    /**
     * The rights Studio's broker user needs on the staging namespace a cross-broker move parks its
     * messages in (ADR-0097). Shown when the source broker refuses to create a staging queue.
     */
    public static final String STAGING_SECURITY_SETTING = """
            <security-setting match="studio.transfer.#">
              <permission type="createAddress"      roles="amq"/>
              <permission type="deleteAddress"      roles="amq"/>
              <permission type="createDurableQueue" roles="amq"/>
              <permission type="deleteDurableQueue" roles="amq"/>
              <permission type="send"               roles="amq"/>
              <permission type="consume"            roles="amq"/>
              <permission type="browse"             roles="amq"/>
            </security-setting>
            """;
}
