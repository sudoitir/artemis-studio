package io.github.sudoitir.artemisstudio.security.oidc;

import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Runs once Spring Security has completed the OIDC authorization-code exchange
 * and holds an {@link OidcUser} principal. Replaces it in the
 * {@link SecurityContext} with a {@link StudioPrincipal} — the one principal
 * shape every other authentication path in the app already produces — via
 * {@link StudioOidcUserService}, then redirects to the SPA root, or to a login
 * error page when the identity matches no configured role mapping and no
 * default role is set (oidc-sso spec).
 */
@Component
@RequiredArgsConstructor
public class OidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final StudioOidcUserService oidcUserService;
    private final SecurityContextRepository securityContextRepository;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
                || !(oauthToken.getPrincipal() instanceof OidcUser oidcUser)) {
            response.sendRedirect("/login?error=oidc");
            return;
        }
        String issuer = oidcUser.getIssuer() != null
                ? oidcUser.getIssuer().toString()
                : oauthToken.getAuthorizedClientRegistrationId();
        StudioOidcUserService.Result result = oidcUserService.provisionAndAuthenticate(oidcUser, issuer);
        switch (result) {
            case StudioOidcUserService.Result.Authenticated authenticated -> {
                establishSession(authenticated.principal(), request, response);
                response.sendRedirect("/");
            }
            case StudioOidcUserService.Result.Refused ignored -> response.sendRedirect("/login?error=oidc-unmapped");
        }
    }

    private void establishSession(StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
