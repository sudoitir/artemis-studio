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

    /**
     * {@code countMessages(filter)} on the queue MBean — the by-filter dry-run estimate, and with a
     * {@link FrozenFilter} the size of a frozen selection.
     */
    public long countMessages(JolokiaBrokerClient client, String queueMbean, String filter) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(queueMbean, "countMessages(java.lang.String)", filter == null ? "" : filter));
        rejectBadFilter(res);
        requireOk(res, "countMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    /**
     * The ids of every message on the queue, in queue order, from one {@code listMessages("")}. For a
     * queue known to be small, such as a transfer's staging queue: it lists the whole queue at once.
     */
    public List<Long> listIds(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(queueMbean, "listMessages(java.lang.String)", ""));
        requireOk(res, "listMessages");
        List<Long> ids = new java.util.ArrayList<>();
        JsonNode listed = client.parsed(res);
        if (listed != null) {
            listed.forEach(m -> ids.add(m.path("messageID").asLong()));
        }
        return ids;
    }

    /** Current {@code MessageCount} of the queue — the purge / retry-all dry-run estimate. */
    public long messageCount(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res = client.single(JolokiaRequest.read(queueMbean, "MessageCount"));
        requireOk(res, "MessageCount");
        JsonNode count = res.attribute("MessageCount");
        return count == null ? 0L : count.asLong();
    }

    /** A queue's depth and the part of it in flight: one read of three attributes. */
    public record QueueDepth(long messageCount, long deliveringCount, long scheduledCount) {
        /** What a browser can see: neither delivered-unacked nor scheduled messages. */
        public long browsable() {
            return Math.max(0, messageCount - deliveringCount - scheduledCount);
        }
    }

    public QueueDepth depth(JolokiaBrokerClient client, String queueMbean) {
        JolokiaResponse res =
                client.single(JolokiaRequest.read(queueMbean, "MessageCount", "DeliveringCount", "ScheduledCount"));
        requireOk(res, "MessageCount");
        return new QueueDepth(
                res.value().path("MessageCount").asLong(),
                res.value().path("DeliveringCount").asLong(),
                res.value().path("ScheduledCount").asLong());
    }

    // ---- by explicit ids (one exec per id; the broker has no id-batch op) ----

    /** Ids sent to the broker in one batch request, and so charged one permit (ADR-0076). */
    static final int BY_ID_BATCH = 50;

    /**
     * What an operation on a list of ids did. When it stopped part-way, {@code error} says why
     * and {@code notDone} holds every id that was not acted on — the ones the broker refused and
     * every id after the batch that failed — so a caller never reports "failed" for an operation
     * that already acted on some messages.
     */
    public record BulkResult(long affected, List<Long> notDone, String error) {
        public boolean partial() {
            return error != null;
        }
    }

    public BulkResult moveByIds(JolokiaBrokerClient client, String queueMbean, List<Long> ids, String targetQueue) {
        return eachId(
                client,
                ids,
                "moveMessage",
                id -> JolokiaRequest.exec(queueMbean, "moveMessage(long,java.lang.String)", id, targetQueue));
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

    /**
     * {@code moveMessages(flushLimit, filter, otherQueue, rejectDuplicates, messageCount)}: moves at
     * most {@code messageCount} matching messages, so one call stays short however deep the queue is.
     * Returns how many the broker moved; fewer than {@code messageCount} means none are left to match.
     */
    public long moveMessages(
            JolokiaBrokerClient client,
            String queueMbean,
            int flushLimit,
            String filter,
            String targetQueue,
            boolean rejectDuplicates,
            int messageCount) {
        return filterExec(
                client,
                queueMbean,
                "moveMessages(int,java.lang.String,java.lang.String,boolean,int)",
                flushLimit,
                filter == null ? "" : filter,
                targetQueue,
                rejectDuplicates,
                messageCount);
    }

    /** {@code copyMessage(id, otherQueue)}: false when the broker found no message with this id. */
    public boolean copyMessage(JolokiaBrokerClient client, String queueMbean, long messageId, String targetQueue) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(queueMbean, "copyMessage(long,java.lang.String)", messageId, targetQueue));
        requireOk(res, "copyMessage");
        return res.value() != null && res.value().asBoolean();
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
        return eachId(client, ids, op, id -> JolokiaRequest.exec(queueMbean, op, id));
    }

    /**
     * Acts on the ids in batch requests of {@value #BY_ID_BATCH} and counts the ones the broker
     * says it acted on. A batch runs every operation in it even after one fails, so every result
     * of that batch is counted before the run stops; later batches are not sent. A failure that
     * acted on nothing at all is thrown as it is, because nothing changed.
     */
    private BulkResult eachId(
            JolokiaBrokerClient client,
            List<Long> ids,
            String op,
            java.util.function.Function<Long, JolokiaRequest> request) {
        long affected = 0;
        for (int from = 0; from < ids.size(); from += BY_ID_BATCH) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + BY_ID_BATCH));
            List<Long> later = ids.subList(from + chunk.size(), ids.size());
            List<JolokiaResponse> responses;
            try {
                responses = client.batch(chunk.stream().map(request).toList());
            } catch (RuntimeException e) {
                if (affected == 0) {
                    throw e;
                }
                return new BulkResult(affected, List.copyOf(ids.subList(from, ids.size())), e.getMessage());
            }
            List<Long> refused = new java.util.ArrayList<>();
            RuntimeException firstError = null;
            for (int i = 0; i < chunk.size(); i++) {
                try {
                    if (i >= responses.size()) {
                        throw new BrokerConnectionException(
                                BrokerConnectionException.Kind.BAD_RESPONSE,
                                "The broker returned fewer results than operations.");
                    }
                    JolokiaResponse res = responses.get(i);
                    requireOk(res, op);
                    if (res.value() != null && res.value().asBoolean()) {
                        affected++;
                    }
                } catch (RuntimeException e) {
                    refused.add(chunk.get(i));
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
            if (firstError != null) {
                if (affected == 0) {
                    throw firstError;
                }
                refused.addAll(later);
                return new BulkResult(affected, List.copyOf(refused), firstError.getMessage());
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
