package io.github.sudoitir.artemisstudio.broker;

/**
 * Constructs Artemis JMX object names for Jolokia requests.
 *
 * <p>The broker's {@code <name>} from {@code broker.xml} is not known at
 * registration, so it is resolved with {@link #BROKER_SEARCH_PATTERN} (a JMX
 * pattern with no trailing {@code ,*}, which matches only the single top-level
 * broker MBean and not its sub-components).
 */
public final class BrokerMBeans {

    public static final String DOMAIN = "org.apache.activemq.artemis";

    /** Matches exactly the top-level broker MBean(s), e.g. {@code org.apache.activemq.artemis:broker="primary"}. */
    public static final String BROKER_SEARCH_PATTERN = DOMAIN + ":broker=*";

    private BrokerMBeans() {}

    /** All acceptor MBeans under a broker, for CORE-protocol detection. */
    public static String acceptorsPattern(String brokerObjectName) {
        return brokerObjectName + ",component=acceptors,name=*";
    }

    /** All address MBeans under a broker. */
    public static String addressesPattern(String brokerObjectName) {
        return brokerObjectName + ",component=addresses,address=*";
    }

    /** One address MBean under a broker. */
    public static String address(String brokerObjectName, String address) {
        return brokerObjectName + ",component=addresses,address=" + quote(address);
    }

    /** One queue MBean under a broker. */
    public static String queue(String brokerObjectName, String address, String queue, String routingType) {
        return brokerObjectName + ",component=addresses,address=" + quote(address)
                + ",subcomponent=queues,routing-type=" + quote(routingType.toLowerCase())
                + ",queue=" + quote(queue);
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }
}
