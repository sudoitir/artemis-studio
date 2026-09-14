package io.github.sudoitir.artemisstudio.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * Put the principal in the session. The framework's load-only
     * {@code SecurityContextHolderFilter} does not save a programmatically established
     * context, so it is saved explicitly.
     */
    public void establish(StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        reissueCsrfToken(request, response);
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
