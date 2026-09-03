package io.github.sudoitir.artemisstudio.sse;

import java.util.Set;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One open SSE connection: its {@link SseEmitter} and the topics it asked for.
 * Identity is the emitter — the same client reconnecting is a new subscriber.
 */
public record Subscriber(SseEmitter emitter, Set<String> topics) {

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
