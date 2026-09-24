package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.InitialInstallers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Starts and ends the server-side session for a principal (identity-and-sessions spec). Every
 * sign-in path ends here, whichever provider established who the caller is.
 */
@Component
@RequiredArgsConstructor
public class SessionAuthentication {

    /** How recent a sign-in or step-up must be for an action that demands one (ADR-0103). */
    public static final Duration REAUTHENTICATION_WINDOW = Duration.ofMinutes(5);

    /** The session attribute holding the {@link Instant} of the last sign-in or step-up. */
    public static final String AUTHENTICATED_AT = SessionAuthentication.class.getName() + ".authenticatedAt";

    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final InitialInstallers initialInstallers;

    /**
     * Put the principal in the session. The framework's load-only
     * {@code SecurityContextHolderFilter} does not save a programmatically established
     * context, so it is saved explicitly. A session that existed before sign-in gets a new id,
     * so an identifier planted or observed before authentication never becomes authenticated
     * (session fixation); local login is a controller, so the filter chain's own fixation
     * strategy never runs for it.
     */
    public void establish(StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(AUTHENTICATED_AT, Instant.now());
        reissueCsrfToken(request, response);
        initialInstallers.grantIfNoneYet(principal.userId());
    }

    /**
     * The signed-in caller proved who they are again (step-up): a new session id, so an identifier
     * observed before the step-up is not the one that carries it, and a fresh authentication time.
     */
    public void reauthenticated(HttpServletRequest request) {
        request.changeSessionId();
        request.getSession().setAttribute(AUTHENTICATED_AT, Instant.now());
    }

    /** When this session last signed in or stepped up; empty without a session. */
    public Optional<Instant> authenticatedAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null
                ? Optional.empty()
                : Optional.ofNullable((Instant) session.getAttribute(AUTHENTICATED_AT));
    }

    /** Whether this session signed in or stepped up within {@link #REAUTHENTICATION_WINDOW}. */
    public boolean recentlyAuthenticated(HttpServletRequest request) {
        return authenticatedAt(request)
                .map(at -> at.isAfter(Instant.now().minus(REAUTHENTICATION_WINDOW)))
                .orElse(false);
    }

    /** Clear the session and its security context. */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.getContext().setAuthentication(null);
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        reissueCsrfToken(request, response);
    }

    /**
     * {@code CsrfAuthenticationStrategy} and {@code CsrfLogoutHandler} clear the previous CSRF
     * cookie on login and logout — a documented SPA gotcha (design.md decision 1) — so both
     * paths save a fresh one explicitly rather than waiting for lazy regeneration.
     */
    private void reissueCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }
}
