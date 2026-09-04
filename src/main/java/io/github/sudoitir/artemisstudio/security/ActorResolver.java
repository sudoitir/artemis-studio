package io.github.sudoitir.artemisstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link Actor} for a mutating action (ADR-0023). Works today with
 * no authentication configured: the principal is {@code "anonymous"}, the source
 * IP comes from {@code getRemoteAddr()}, and the request id is the inbound
 * {@code X-Request-Id} header or a fresh UUID. When Phase 8 wires Spring
 * Security, {@code SecurityContextHolder} starts returning a real principal and
 * this class needs no change.
 */
@Component
public class ActorResolver {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    public Actor resolve() {
        HttpServletRequest request = currentRequest();
        return new Actor(principalName(), sourceIp(request), requestId(request), null);
    }

    /** For scheduler-originated audit rows. */
    public Actor system() {
        return Actor.system();
    }

    private static String principalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return Actor.ANONYMOUS;
    }

    private static String sourceIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    private static String requestId(HttpServletRequest request) {
        if (request != null) {
            String header = request.getHeader(REQUEST_ID_HEADER);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
