package io.github.sudoitir.artemisstudio.kernel.replica;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A stream frame for one cluster's topic. {@code data} is null for a signal-only frame, which is also
 * what a frame too large for the bus becomes; {@code id} is the SSE id, null for a frame with none;
 * {@code about} names the queues and addresses it concerns, null for a frame about the cluster itself.
 * Nulls are left off the wire, so they arrive as nulls. Published as a Spring event on every replica
 * when it arrives.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusFrame(UUID clusterId, String topic, JsonNode data, String id, About about) implements BusMessage {

    /** A frame about the cluster as a whole. */
    public BusFrame(UUID clusterId, String topic, JsonNode data, String id) {
        this(clusterId, topic, data, id, null);
    }

    /**
     * The queues and addresses a frame is about. A subscriber who may not see every frame of the topic
     * receives it when they may read at least one of them, cut to those.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record About(List<String> queues, List<String> addresses) {

        /** More names than this make a frame too big for the bus, so it is sent about nothing in particular. */
        public static final int MAX_NAMES = 100;

        public About {
            queues = List.copyOf(queues == null ? List.of() : queues);
            addresses = List.copyOf(addresses == null ? List.of() : addresses);
        }

        public static About queue(String name) {
            return of(List.of(name), List.of());
        }

        public static About address(String name) {
            return of(List.of(), List.of(name));
        }

        /**
         * The frame's subjects, or {@code null} when there are none or too many to carry: such a frame is
         * about the cluster, and only a subscriber who may see all of it receives it.
         */
        public static About of(Collection<String> queues, Collection<String> addresses) {
            List<String> q = queues.stream()
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .toList();
            List<String> a = addresses.stream()
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .toList();
            if (q.size() + a.size() == 0 || q.size() + a.size() > MAX_NAMES) {
                return null;
            }
            return new About(q, a);
        }
    }
}
