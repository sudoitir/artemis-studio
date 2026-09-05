package io.github.sudoitir.artemisstudio.broker;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One entry in a Jolokia request. A {@link JolokiaBrokerClient#batch} call sends
 * a JSON array of these; a single call sends one object.
 *
 * <p>Only the fields relevant to {@code type} are serialised (null fields are
 * omitted), so a {@code read} carries {@code attribute} and an {@code exec}
 * carries {@code operation} / {@code arguments}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JolokiaRequest(
        String type, String mbean, List<String> attribute, String operation, List<Object> arguments) {

    /** Read one or more attributes of an MBean. In a POST body attributes are an array. */
    public static JolokiaRequest read(String mbean, String... attributes) {
        return new JolokiaRequest("read", mbean, List.of(attributes), null, null);
    }

    /**
     * Read <em>every</em> attribute of an MBean. Jolokia treats an omitted
     * {@code attribute} as "all"; an empty array is not the same thing.
     */
    public static JolokiaRequest readAll(String mbean) {
        return new JolokiaRequest("read", mbean, null, null, null);
    }

    /** Invoke a management operation. {@code operation} includes the signature, e.g. {@code listQueues(java.lang.String,int,int)}. */
    public static JolokiaRequest exec(String mbean, String operation, Object... arguments) {
        return new JolokiaRequest("exec", mbean, null, operation, List.of(arguments));
    }

    /** Search for MBean names matching a JMX pattern. */
    public static JolokiaRequest search(String pattern) {
        return new JolokiaRequest("search", pattern, null, null, null);
    }
}
