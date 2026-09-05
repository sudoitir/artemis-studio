package io.github.sudoitir.artemisstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link Actor} for a mutating action (ADR-0041, superseding
 * ADR-0023). The username and user id come from the authenticated
 * {@link StudioPrincipal} when one is present; otherwise the actor is
 * {@code "anonymous"} with no user id. The source IP comes from
 * {@code getRemoteAddr()}, and the request id is the inbound
 * {@code X-Request-Id} header or a fresh UUID.
 */
@Component
public class ActorResolver {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    public Actor resolve() {
        HttpServletRequest request = currentRequest();
        StudioPrincipal principal = currentPrincipal();
        String username = principal != null ? principal.getUsername() : Actor.ANONYMOUS;
        UUID userId = principal != null ? principal.userId() : null;
        return new Actor(username, sourceIp(request), requestId(request), userId);
    }

    /** For scheduler-originated audit rows. */
    public Actor system() {
        return Actor.system();
    }

    private static StudioPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof StudioPrincipal p) {
            return p;
        }
        return null;
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
