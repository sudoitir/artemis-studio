package io.github.sudoitir.artemisstudio.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Request bodies for the Phase 3 message API (ADR-0021). */
public final class MessageRequests {

    private MessageRequests() {}

    /**
     * A message to enqueue. Over Jolokia the {@code body} is text (non-negotiable
     * #5 — binary is Phase 4). {@code type} is the Artemis message type int
     * (3 = text); {@code headers} are the well-known JMS headers, {@code properties}
     * the arbitrary application properties.
     */
    public record SendMessageRequest(
            @NotNull Integer type,
            boolean durable,
            String body,
            Map<String, Object> headers,
            Map<String, Object> properties) {

        public SendMessageRequest {
            headers = headers == null ? Map.of() : headers;
            properties = properties == null ? Map.of() : properties;
            body = body == null ? "" : body;
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
