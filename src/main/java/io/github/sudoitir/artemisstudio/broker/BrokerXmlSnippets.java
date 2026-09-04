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
