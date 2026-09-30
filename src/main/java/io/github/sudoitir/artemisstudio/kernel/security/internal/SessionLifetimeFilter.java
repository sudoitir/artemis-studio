package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session that should no longer count as signed in, right after the security context is
 * loaded, so the request carries on as the anonymous caller and the chain answers {@code 401}
 * where authentication is required. The one place session lifetimes are enforced (ADR-0144);
 * for now it ends a session that has a principal but no {@code SessionFacts}, which is what a
 * session created before facts existed looks like.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    private final SessionAuthentication sessions;

    public SessionLifetimeFilter(SessionAuthentication sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof StudioPrincipal
                && sessions.facts(request).isEmpty()) {
            sessions.end(request, response);
        }
        chain.doFilter(request, response);
    }
}
