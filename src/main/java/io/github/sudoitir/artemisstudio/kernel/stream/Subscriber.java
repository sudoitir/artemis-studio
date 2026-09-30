package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One open SSE connection: its {@link SseEmitter}, the topics it asked for, the id of the session
 * that opened it, which is null for a caller with no session, and the id of the API token that
 * authenticated it, which is null for a session. Identity is the emitter — the same client
 * reconnecting is a new subscriber. The stream ends with its session ({@link SseHub#onSessionEnded},
 * {@link SseHub#closeEndedSessions}) or when its token stops being accepted
 * ({@link SseHub#closeEndedTokens}).
 */
public record Subscriber(SseEmitter emitter, Set<String> topics, String sessionId, UUID tokenId) {

    /** A subscriber that has a session and no token. */
    public Subscriber(SseEmitter emitter, Set<String> topics, String sessionId) {
        this(emitter, topics, sessionId, null);
    }

    /**
     * A subscriber for the session that made {@code request}, if it has one, and the API token that
     * authenticated it, if one did.
     */
    public static Subscriber of(SseEmitter emitter, Set<String> topics, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID tokenId = auth != null && auth.getPrincipal() instanceof TokenPrincipal p ? p.tokenId() : null;
        return new Subscriber(emitter, topics, session == null ? null : session.getId(), tokenId);
    }

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
