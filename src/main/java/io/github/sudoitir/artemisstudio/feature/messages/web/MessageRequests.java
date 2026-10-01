package io.github.sudoitir.artemisstudio.feature.messages.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Request bodies for the Phase 3 message API (ADR-0021). */
public final class MessageRequests {

    private MessageRequests() {}

    /**
     * A message to enqueue. {@code type} is the Artemis message type int
     * (3 = text, 4 = bytes); {@code headers} are the JMS headers
     * {@code correlationId}, {@code type}, {@code replyTo} (a queue name), {@code groupId} and
     * {@code groupSeq}, the names the message detail shows; any other header is refused.
     * {@code properties} are the application properties, each named by a Java identifier that is not
     * a selector keyword and does not start with {@code JMS}. Over a Core connection
     * a property keeps its type; over the management channel it is sent as its text. When
     * {@code bodyBase64} is true and the cluster has a Core connection, the body
     * is base64-decoded and sent as a bytes message; otherwise the body is text.
     */
    public record SendMessageRequest(
            @NotNull Integer type,
            boolean durable,
            String body,
            Boolean bodyBase64,
            Map<String, Object> headers,
            Map<String, Object> properties) {

        public SendMessageRequest {
            headers = headers == null ? Map.of() : headers;
            properties = properties == null ? Map.of() : properties;
            body = body == null ? "" : body;
            bodyBase64 = Boolean.TRUE.equals(bodyBase64);
        }
    }

    /**
     * Move / retry / delete / expire, either by explicit ids or by a selector.
     * Exactly one of {@code messageIds} / {@code filter} is set; {@code targetQueue}
     * is required only for {@code MOVE}.
     */
    public record MessageActionRequest(List<Long> messageIds, String filter, String targetQueue) {

        public boolean byFilter() {
            return filter != null && !filter.isBlank();
        }

        public List<Long> ids() {
            return messageIds == null ? List.of() : messageIds;
        }
    }
}
