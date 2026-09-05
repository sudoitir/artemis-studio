package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.service.MustChangePasswordException;
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
 * Blocks every request from a user flagged {@code mustChangePassword} except the
 * handful needed to clear that flag (identity-and-sessions spec: "Login is
 * restricted until the password is changed"). Delegates the resulting exception
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
                && !ALLOWED_PATHS.contains(request.getRequestURI())) {
            exceptionResolver.resolveException(request, response, null, new MustChangePasswordException());
            return;
        }
        chain.doFilter(request, response);
    }
}
