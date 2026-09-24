package io.github.sudoitir.artemisstudio.kernel.security.internal;

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
 * Blocks every protected request from a user flagged {@code mustChangePassword} except the
 * handful needed to clear that flag (identity-and-sessions spec: "Login is
 * restricted until the password is changed"). The SPA shell and its static assets stay
 * reachable, or the browser could not load the change-password page itself. Delegates the resulting exception
 * to {@link HandlerExceptionResolver} so it renders through the same
 * {@code ApiExceptionHandler} a controller-thrown exception would.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS =
            Set.of("/api/v1/auth/password", "/api/v1/auth/logout", "/api/v1/auth/me");

    private final HandlerExceptionResolver exceptionResolver;

    public MustChangePasswordFilter(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof StudioPrincipal principal
                && principal.mustChangePassword()
                && isProtected(request.getRequestURI())
                && !ALLOWED_PATHS.contains(request.getRequestURI())) {
            exceptionResolver.resolveException(request, response, null, new MustChangePasswordException());
            return;
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
