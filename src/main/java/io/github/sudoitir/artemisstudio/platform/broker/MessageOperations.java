package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 3 mutating message operations over Jolokia (ADR-0021). Each method is
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
        JsonNode count = res.attribute("MessageCount");
        return count == null ? 0L : count.asLong();
    }

    // ---- by explicit ids (one exec per id; the broker has no id-batch op) ----

    /**
     * What an operation on a list of ids did. When it stopped part-way, {@code error} says why
     * and {@code notAttempted} holds the id that failed and every id after it, so a caller never
     * reports "failed" for an operation that already acted on some messages.
     */
    public record BulkResult(long affected, List<Long> notAttempted, String error) {
        public boolean partial() {
            return error != null;
        }
    }

    public BulkResult moveByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids, String targetQueue) {
        return eachId(
                ids,
                "moveMessage",
                id -> client.single(
                        JolokiaRequest.exec(queueMbean, "moveMessage(long,java.lang.String)", id, targetQueue)));
    }

    public BulkResult retryByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "retryMessage(long)", ids);
    }

    public BulkResult deleteByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "removeMessage(long)", ids);
    }

    public BulkResult expireByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids) {
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

    private BulkResult countTrue(JolokiaBrokerClient client, String queueMbean, String op, List<Long> ids) {
        return eachId(ids, op, id -> client.single(JolokiaRequest.exec(queueMbean, op, id)));
    }

    /**
     * Acts on each id in order and counts the ones the broker says it acted on. A failure on the
     * first id is thrown as it is — nothing was done, so it is a plain failure; a later one ends
     * the run as partial.
     */
    private BulkResult eachId(List<Long> ids, String op, java.util.function.Function<Long, JolokiaResponse> call) {
        long affected = 0;
        for (int i = 0; i < ids.size(); i++) {
            try {
                JolokiaResponse res = call.apply(ids.get(i));
                requireOk(res, op);
                if (res.value() != null && res.value().asBoolean()) {
                    affected++;
                }
            } catch (RuntimeException e) {
                if (i == 0) {
                    throw e;
                }
                return new BulkResult(affected, List.copyOf(ids.subList(i, ids.size())), e.getMessage());
            }
        }
        return new BulkResult(affected, List.of(), null);
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
