package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.BearerIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates an {@code Authorization: Bearer} request through the bearer identity providers,
 * independently of any session cookie (api-tokens spec). Placed after
 * {@code SecurityContextHolderFilter}, which would otherwise overwrite this filter's
 * authentication with the session-less empty context it loads.
 */
class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final List<IdentityProviders> contributions;

    BearerAuthenticationFilter(List<IdentityProviders> contributions) {
        this.contributions = contributions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Optional<StudioPrincipal> principal = contributions.stream()
                    .flatMap(c -> c.providers().stream())
                    .filter(p -> p instanceof BearerIdentityProvider)
                    .map(p -> ((BearerIdentityProvider) p).authenticate(token))
                    .flatMap(Optional::stream)
                    .findFirst();
            principal.ifPresent(p -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
