package io.github.sudoitir.artemisstudio.kernel.stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One open SSE connection: its {@link SseEmitter}, the topics it asked for and the id of the
 * session that opened it, which is null for a caller with no session (a bearer token). Identity is
 * the emitter — the same client reconnecting is a new subscriber. The stream ends with its session
 * ({@link SseHub#onSessionEnded}, {@link SseHub#closeEndedSessions}).
 */
public record Subscriber(SseEmitter emitter, Set<String> topics, String sessionId) {

    /** A subscriber for the session that made {@code request}, if it has one. */
    public static Subscriber of(SseEmitter emitter, Set<String> topics, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return new Subscriber(emitter, topics, session == null ? null : session.getId());
    }

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
