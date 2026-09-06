package io.github.sudoitir.artemisstudio.broker;

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
     * <p>Shown whenever native detection is not reported as configured — including
     * when it is UNKNOWN, which is the usual answer: the slice-0 spike confirmed
     * {@code getAddressSettingsAsJSON} returns only
     * {@code slowConsumerThresholdMeasurementUnit}, never the threshold itself, so
     * Studio cannot observe whether it is set (non-negotiable #5).
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
}
