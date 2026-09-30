package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.MfaEnrolmentRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.MustChangePasswordException;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Blocks every protected request from a session that is signed in but restricted, except the
 * handful needed to lift the restriction (identity-and-sessions spec). Two restrictions, checked in
 * this order:
 *
 * <ol>
 *   <li>the user must change their password: {@code 423 must-change-password};
 *   <li>the user's role requires a second factor and they have none: {@code 423
 *       mfa-enrolment-required}, until they enrol one (ADR-0143).
 * </ol>
 *
 * The SPA shell and its static assets stay reachable, or the browser could not load the page that
 * lifts the restriction. Delegates the resulting exception to {@link HandlerExceptionResolver} so it
 * renders through the same {@code ApiExceptionHandler} a controller-thrown exception would.
 */
public class RestrictedSessionFilter extends OncePerRequestFilter {

    private static final Set<String> AS_MUST_CHANGE_PASSWORD =
            Set.of("/api/v1/auth/password", "/api/v1/auth/logout", "/api/v1/auth/me");

    /** {@code METHOD path}, exactly: enrolling is all a restricted session may do beyond looking at itself and leaving. */
    private static final Set<String> AS_ENROLMENT_REQUIRED = Set.of(
            "POST /api/v1/auth/mfa/totp",
            "POST /api/v1/auth/mfa/totp/confirm",
            "POST /api/v1/auth/mfa/webauthn/options",
            "POST /api/v1/auth/mfa/webauthn",
            "GET /api/v1/auth/mfa",
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/logout");

    private final HandlerExceptionResolver exceptionResolver;

    public RestrictedSessionFilter(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof StudioPrincipal principal
                && isProtected(request.getRequestURI())) {
            String path = request.getRequestURI();
            if (principal.mustChangePassword() && !AS_MUST_CHANGE_PASSWORD.contains(path)) {
                exceptionResolver.resolveException(request, response, null, new MustChangePasswordException());
                return;
            }
            if (!principal.mustChangePassword()
                    && principal.secondFactorEnrolmentRequired()
                    && !AS_ENROLMENT_REQUIRED.contains(request.getMethod() + " " + path)) {
                exceptionResolver.resolveException(request, response, null, new MfaEnrolmentRequiredException());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The paths {@code SecurityConfig} requires authentication for; everything else is the public SPA shell. */
    private static boolean isProtected(String path) {
        return path.startsWith("/api/")
                || path.equals("/mcp")
                || path.startsWith("/mcp/")
                || path.startsWith("/plugin-ui/")
                || path.equals("/actuator/refresh");
    }
}
