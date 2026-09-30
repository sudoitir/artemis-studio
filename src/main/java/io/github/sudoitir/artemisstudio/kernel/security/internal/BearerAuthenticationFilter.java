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
 *
 * <p>Any other {@code Authorization} header, and a bearer token no provider accepts, is answered
 * with 401 here. Only a request this filter authenticated skips the CSRF check
 * ({@link #AUTHENTICATED}); otherwise a junk header plus the session cookie would skip it too.
 */
class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** Request attribute set once a bearer token authenticated the request. */
    static final String AUTHENTICATED = BearerAuthenticationFilter.class.getName() + ".AUTHENTICATED";

    private final List<IdentityProviders> contributions;

    BearerAuthenticationFilter(List<IdentityProviders> contributions) {
        this.contributions = contributions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<StudioPrincipal> principal = header.startsWith(BEARER_PREFIX)
                ? authenticate(header.substring(BEARER_PREFIX.length()).trim())
                : Optional.empty();
        if (principal.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        StudioPrincipal p = principal.get();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.setAttribute(AUTHENTICATED, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    private Optional<StudioPrincipal> authenticate(String token) {
        return contributions.stream()
                .flatMap(c -> c.providers().stream())
                .filter(BearerIdentityProvider.class::isInstance)
                .map(BearerIdentityProvider.class::cast)
                .map(p -> p.authenticate(token))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
