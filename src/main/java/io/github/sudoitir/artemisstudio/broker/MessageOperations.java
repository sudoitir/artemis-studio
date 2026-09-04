package io.github.sudoitir.artemisstudio.broker;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 3 mutating message operations over Jolokia (ADR-0020). Each method is
 * exactly one {@code exec} — never a dry-run and an act in the same POST
 * (non-negotiable #1). Results are the broker's own affected counts, not
 * estimates. Invalid selectors ({@code AMQ229020}) surface as
 * {@link IllegalArgumentException} (→ 400).
 */
@Component
public class MessageOperations {

    private static final String SEND_SIG =
            "sendMessage(java.util.Map,int,java.lang.String,boolean,java.lang.String,java.lang.String)";

    /** Enqueue one message on the address MBean. Returns the broker-assigned id, or {@code null}. */
    public String send(
            JolokiaBrokerClient client,
            String addressMbean,
            Map<String, Object> headers,
            int type,
            String body,
            boolean durable) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(
                addressMbean,
                SEND_SIG,
                headers == null ? Map.of() : headers,
                type,
                body == null ? "" : body,
                durable,
                "",
                ""));
        requireOk(res, "sendMessage");
        JsonNode v = res.value();
        return v == null || v.isNull() ? null : v.asText();
    }

    /** {@code countMessages(filter)} on the queue MBean — the by-filter dry-run estimate. */
    public long countMessages(JolokiaBrokerClient client, String queueMbean, String filter) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(queueMbean, "countMessages(java.lang.String)", filter == null ? "" : filter));
        rejectBadFilter(res);
        requireOk(res, "countMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    /** Current {@code MessageCount} of the queue — the purge / retry-all dry-run estimate. */
    public long messageCount(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res = client.single(JolokiaRequest.read(queueMbean, "MessageCount"));
        requireOk(res, "MessageCount");
        return res.value() == null ? 0L : res.value().asLong();
    }

    // ---- by explicit ids (one exec per id; the broker has no id-batch op) ----

    public long moveByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids, String targetQueue) {
        long moved = 0;
        for (long id : ids) {
            JolokiaResponse res = client.single(
                    JolokiaRequest.exec(queueMbean, "moveMessage(long,java.lang.String)", id, targetQueue));
            requireOk(res, "moveMessage");
            if (res.value() != null && res.value().asBoolean()) {
                moved++;
            }
        }
        return moved;
    }

    public long retryByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "retryMessage(long)", ids);
    }

    public long deleteByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "removeMessage(long)", ids);
    }

    public long expireByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "expireMessage(long)", ids);
    }

    // ---- by selector (one exec; the broker returns the affected count) ----

    public long moveByFilter(JolokiaBrokerClient client, String queueMbean, String filter, String targetQueue) {
        return filterExec(client, queueMbean, "moveMessages(java.lang.String,java.lang.String)", filter, targetQueue);
    }

    public long deleteByFilter(JolokiaBrokerClient client, String queueMbean, String filter) {
        return filterExec(client, queueMbean, "removeMessages(java.lang.String)", filter);
    }

    public long expireByFilter(JolokiaBrokerClient client, String queueMbean, String filter) {
        return filterExec(client, queueMbean, "expireMessages(java.lang.String)", filter);
    }

    /** Retry every message on the queue — Artemis has no by-filter retry. Returns the count retried. */
    public long retryAll(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(queueMbean, "retryMessages()"));
        requireOk(res, "retryMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    /** {@code removeAllMessages()} — the purge. Returns the count removed. */
    public long purge(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(queueMbean, "removeAllMessages()"));
        requireOk(res, "removeAllMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    // ---- helpers -------------------------------------------------------

    private long countTrue(JolokiaBrokerClient client, String queueMbean, String op, List<Long> ids) {
        long n = 0;
        for (long id : ids) {
            JolokiaResponse res = client.single(JolokiaRequest.exec(queueMbean, op, id));
            requireOk(res, op);
            if (res.value() != null && res.value().asBoolean()) {
                n++;
            }
        }
        return n;
    }

    private long filterExec(JolokiaBrokerClient client, String queueMbean, String op, Object... args) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(queueMbean, op, args));
        rejectBadFilter(res);
        requireOk(res, op);
        return res.value() == null ? 0L : res.value().asLong();
    }

    private static void rejectBadFilter(JolokiaResponse res) {
        if (!res.ok() && res.error() != null) {
            String e = res.error();
            if (e.contains("AMQ229020") || e.toLowerCase().contains("invalid filter")) {
                throw new IllegalArgumentException("Invalid message filter.");
            }
        }
    }

    private static void requireOk(JolokiaResponse res, String op) {
        if (!res.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    op + " failed: " + (res.error() != null ? res.error() : "status " + res.status()));
        }
    }
}
