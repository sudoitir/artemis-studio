package io.github.sudoitir.artemisstudio.broker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Closing connections, sessions and an address's consumers, over Jolokia
 * (ADR-0057). Each close is exactly one {@code exec} — never a read and an act
 * in the same POST (non-negotiable #1); the pre-close read that identifies the
 * target is a separate, batched call.
 *
 * <p>The signatures were taken from {@code ActiveMQServerControl} in
 * {@code artemis-core-client 2.56.0}, not from memory:
 *
 * <pre>
 *   boolean closeConnectionWithID(String connectionID)
 *   boolean closeSessionWithID(String connectionID, String sessionID)
 *   boolean closeConsumerConnectionsForAddress(String address)
 * </pre>
 *
 * <p><b>A missing target is not an error.</b> None of these raise; they answer
 * {@code false} when nothing matched. There is no {@code AMQ…} code to classify,
 * which is why {@link ManagementRefusal} plays no part here and why the
 * already-gone verdict is carried as a boolean all the way up (ADR-0057 D2).
 *
 * <p>Rows are read through the broker's own {@code listX} filter rather than by
 * fetching every row and matching in Java: a close is aimed at exactly one
 * connection, and asking the broker for one row is the difference between a
 * bounded call and a full connection dump on a busy node.
 */
@Component
public class ConnectionOperations {

    /**
     * Everything the confirmation and the audit record need about a connection,
     * read immediately before it is closed (ADR-0057 D3). After the close none of
     * it is resolvable, which is the whole reason it is captured.
     *
     * @param messagesInTransit in-flight messages that will return to their queues
     *     with an increased delivery count; {@code null} when the broker did not
     *     report it, which is stated rather than shown as zero
     */
    public record ConnectionSnapshot(
            String connectionId,
            String clientId,
            String remoteAddress,
            String user,
            String protocol,
            long sessionCount,
            long consumerCount,
            Long messagesInTransit) {

        /**
         * What a human can recognise this connection by, and therefore what the
         * typed confirmation is against (D3). Never the connection id: an operator
         * cannot tell from {@code a3f1-…} that they have the right one.
         */
        public String label() {
            return blank(clientId) ? (blank(remoteAddress) ? connectionId : remoteAddress) : clientId;
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    private final ObjectMapper mapper;
    private final BrokerListOps listOps;

    public ConnectionOperations(ObjectMapper mapper, BrokerListOps listOps) {
        this.mapper = mapper;
        this.listOps = listOps;
    }

    // ---- reads -----------------------------------------------------------

    /**
     * The pre-close snapshot of one connection, or {@code null} when the node no
     * longer has it — the already-gone verdict (D2).
     *
     * <p>Two batched POSTs: the connection and its sessions together, then one
     * {@code listConsumers} per session in a single bulk request. The second is
     * skipped entirely when the connection carries no sessions.
     */
    public ConnectionSnapshot readConnection(JolokiaBrokerClient client, String connectionId) {
        List<JolokiaResponse> head = client.batch(List.of(
                listRequest(client, "listConnections", filter("connectionID", connectionId)),
                listRequest(client, "listSessions", filter("connectionID", connectionId))));
        JsonNode connection = firstRow(client, head.get(0));
        if (connection == null) {
            return null;
        }
        List<JsonNode> sessions = rows(client, head.get(1));

        long consumers = 0;
        List<String> sessionIds = new ArrayList<>();
        for (JsonNode session : sessions) {
            consumers += BrokerListOps.num(session, "consumerCount");
            String id = BrokerListOps.str(session, "id");
            if (id != null && !id.isBlank()) {
                sessionIds.add(id);
            }
        }

        return new ConnectionSnapshot(
                connectionId,
                BrokerListOps.str(connection, "clientID"),
                BrokerListOps.str(connection, "remoteAddress"),
                BrokerListOps.str(connection, "users"),
                BrokerListOps.str(connection, "protocol"),
                // The connection's own sessionCount can disagree with the rows the
                // session list returned; the rows are what the close will actually
                // take with it, so they are what the operator is shown.
                sessions.isEmpty() ? BrokerListOps.num(connection, "sessionCount") : sessions.size(),
                consumers,
                inTransit(client, sessionIds));
    }

    /** The connection a session belongs to, or {@code null} when the session is gone. */
    public String connectionIdOfSession(JolokiaBrokerClient client, String sessionId) {
        JsonNode row = firstRow(client, listRequest(client, "listSessions", filter("id", sessionId)));
        return row == null ? null : BrokerListOps.str(row, "connectionID");
    }

    /** The session a consumer belongs to, or {@code null} when the consumer is gone. */
    public String sessionIdOfConsumer(JolokiaBrokerClient client, String consumerId) {
        JsonNode row = firstRow(client, listRequest(client, "listConsumers", filter("id", consumerId)));
        return row == null ? null : BrokerListOps.str(row, "session");
    }

    /**
     * The pre-close snapshot of one session. Shaped as a connection snapshot with
     * a session count of one, so every kind of close reports the same thing.
     *
     * <p>The remote address is taken from the session's <em>connection</em>, not the
     * session row, which does not carry one. Without it a session whose
     * {@code clientID} is blank — the ordinary case for a CORE client, as this
     * project's own captured broker fixture shows — would fall through to the
     * opaque connection id as its confirmation token, which is precisely what
     * ADR-0057 D3 forbids.
     */
    public ConnectionSnapshot readSession(JolokiaBrokerClient client, String sessionId) {
        JsonNode row = firstRow(client, listRequest(client, "listSessions", filter("id", sessionId)));
        if (row == null) {
            return null;
        }
        String connectionId = BrokerListOps.str(row, "connectionID");
        JsonNode connection = connectionId == null
                ? null
                : firstRow(client, listRequest(client, "listConnections", filter("connectionID", connectionId)));
        return new ConnectionSnapshot(
                connectionId,
                firstNonBlank(BrokerListOps.str(row, "clientID"), str(connection, "clientID")),
                str(connection, "remoteAddress"),
                firstNonBlank(BrokerListOps.str(row, "user"), str(connection, "users")),
                str(connection, "protocol"),
                1,
                BrokerListOps.num(row, "consumerCount"),
                inTransit(client, List.of(sessionId)));
    }

    private static String str(JsonNode row, String field) {
        return row == null ? null : BrokerListOps.str(row, field);
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    /**
     * How many consumers are bound to an address on this node — the address-scoped
     * close's per-node estimate, which the bulk cap is checked against (D6).
     */
    public long countConsumersForAddress(JolokiaBrokerClient client, String address) {
        return listOps.fetch(client, "listConsumers", filter("address", address), -1, -1)
                .count();
    }

    // ---- closes (one exec each) -------------------------------------------

    /** @return true when a connection was closed; false when the node had none with that id. */
    public boolean closeConnection(JolokiaBrokerClient client, String brokerMbean, String connectionId) {
        return closed(client.single(
                JolokiaRequest.exec(brokerMbean, "closeConnectionWithID(java.lang.String)", connectionId)));
    }

    /** @return true when a session was closed; false when it was already gone. */
    public boolean closeSession(JolokiaBrokerClient client, String brokerMbean, String connectionId, String sessionId) {
        return closed(client.single(JolokiaRequest.exec(
                brokerMbean, "closeSessionWithID(java.lang.String,java.lang.String)", connectionId, sessionId)));
    }

    /** @return true when at least one consumer connection was closed on this node. */
    public boolean closeConsumerConnectionsForAddress(JolokiaBrokerClient client, String brokerMbean, String address) {
        return closed(client.single(
                JolokiaRequest.exec(brokerMbean, "closeConsumerConnectionsForAddress(java.lang.String)", address)));
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * In-flight messages held across a set of sessions, or {@code null} when there
     * is nothing to ask about. One bulk POST holds every session's consumer list.
     */
    private Long inTransit(JolokiaBrokerClient client, List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return null;
        }
        List<JolokiaRequest> requests = sessionIds.stream()
                .map(id -> listRequest(client, "listConsumers", filter("session", id)))
                .toList();
        long total = 0;
        for (JolokiaResponse entry : client.batch(requests)) {
            for (JsonNode consumer : rows(client, entry)) {
                total += BrokerListOps.num(consumer, "messagesInTransit");
            }
        }
        return total;
    }

    /** The broker's own management filter, so one row comes back instead of every row. */
    private String filter(String field, String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("operation", "EQUALS");
        f.put("value", value);
        return mapper.writeValueAsString(f);
    }

    private JolokiaRequest listRequest(JolokiaBrokerClient client, String op, String options) {
        return JolokiaRequest.exec(
                client.resolveBrokerObjectName(), op + "(java.lang.String,int,int)", options, -1, -1);
    }

    private JsonNode firstRow(JolokiaBrokerClient client, JolokiaRequest request) {
        return firstRow(client, client.single(request));
    }

    private JsonNode firstRow(JolokiaBrokerClient client, JolokiaResponse entry) {
        List<JsonNode> rows = rows(client, entry);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<JsonNode> rows(JolokiaBrokerClient client, JolokiaResponse entry) {
        JsonNode data = client.parsed(entry).get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        data.forEach(rows::add);
        return rows;
    }

    /**
     * A close's answer. The broker returns {@code false} for a target it does not
     * have — no error code, no exception — so a failed <em>response</em> is a
     * connection problem and a {@code false} value is the already-gone verdict.
     */
    private static boolean closed(JolokiaResponse res) {
        if (!res.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "close failed: " + (res.error() != null ? res.error() : "status " + res.status()));
        }
        return res.value() != null && res.value().asBoolean();
    }
}
