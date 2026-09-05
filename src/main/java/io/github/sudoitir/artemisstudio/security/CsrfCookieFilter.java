package io.github.sudoitir.artemisstudio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred {@link CsrfToken} to resolve on every request, so the
 * {@code XSRF-TOKEN} cookie is set on the very first request a browser makes —
 * not only after a request that happens to read it. Without this, an SPA's
 * first-ever login attempt has no cookie to echo back as {@code X-XSRF-TOKEN}
 * and is rejected by {@code CsrfFilter} before authentication is even
 * evaluated (identity-and-sessions spec, design.md decision 1's CSRF note).
 * The documented pattern for {@code CsrfTokenRequestAttributeHandler}-based
 * SPA setups: {@code CsrfFilter} only resolves the token lazily via the
 * request attribute it sets; something downstream has to actually read it.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken(); // triggers CsrfTokenRepository#saveToken via the deferred supplier
        }
        chain.doFilter(request, response);
    }
}
