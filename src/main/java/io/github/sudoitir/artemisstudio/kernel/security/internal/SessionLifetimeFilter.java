package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.SessionLifetimes;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session that should no longer count as signed in, right after the security context is
 * loaded, so the request carries on as the anonymous caller and the chain answers {@code 401}
 * where authentication is required. The one place session lifetimes are enforced (ADR-0144): a
 * session ends when it has no {@code SessionFacts} (what a session created before facts existed
 * looks like), when it is older than the absolute lifetime, or when the user has done nothing for
 * longer than the idle timeout.
 *
 * <p>Only what the user did counts as activity: a request that changes something, or one carrying
 * {@link #ACTIVITY_HEADER}, which the console sets on requests made within a minute of a click or
 * key press. Polling and the event stream carry no such header, so an unattended tab goes idle.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    public static final String ACTIVITY_HEADER = "X-Studio-Activity";

    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final SessionAuthentication sessions;
    private final SessionLifetimes lifetimes;

    public SessionLifetimeFilter(SessionAuthentication sessions, SessionLifetimes lifetimes) {
        this.sessions = sessions;
        this.lifetimes = lifetimes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StudioPrincipal) {
            var facts = sessions.facts(request);
            if (facts.isEmpty() || expired(facts.get(), request)) {
                sessions.end(request, response);
            } else {
                if (countsAsActivity(request)) {
                    sessions.recordActivity(request);
                }
                sessions.syncMaxInactiveInterval(request.getSession());
            }
        }
        chain.doFilter(request, response);
    }

    private boolean expired(SessionFacts facts, HttpServletRequest request) {
        Instant now = Instant.now();
        Instant lastActivity = sessions.lastActivityAt(request).orElse(facts.signedInAt());
        return now.isAfter(facts.signedInAt().plus(lifetimes.absoluteLifetime()))
                || now.isAfter(lastActivity.plus(lifetimes.idleTimeout()));
    }

    private static boolean countsAsActivity(HttpServletRequest request) {
        return !READ_ONLY_METHODS.contains(request.getMethod()) || "1".equals(request.getHeader(ACTIVITY_HEADER));
    }
}
