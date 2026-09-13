package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.ExternalIdentity;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProvisioner;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Runs once Spring Security has completed the OIDC authorization-code exchange. Hands the
 * identity to the kernel's {@link IdentityProvisioner}, keyed by the client registration id, and
 * replaces the {@link OidcUser} with the {@link StudioPrincipal} every other sign-in produces.
 * Redirects to the SPA root, or to a login error when no group mapping or default role applies
 * (oidc-sso spec).
 */
@Component
@RequiredArgsConstructor
public class OidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final IdentityProvisioner provisioner;
    private final SessionAuthentication sessions;
    private final AuthenticationAudit audit;
    private final OidcProperties properties;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
                || !(oauthToken.getPrincipal() instanceof OidcUser oidcUser)) {
            response.sendRedirect("/login?error=oidc");
            return;
        }
        ExternalIdentity identity = new ExternalIdentity(
                oauthToken.getAuthorizedClientRegistrationId(),
                oidcUser.getSubject(),
                usernameFor(oidcUser),
                oidcUser.getEmail(),
                groups(oidcUser.getClaims().get(properties.oidcClaim())));
        AuthenticationAudit.Attempt attempt = audit.loginAttempted(identity.username(), request);
        Optional<StudioPrincipal> principal = provisioner.provision(identity);
        if (principal.isEmpty()) {
            attempt.failed("no group mapping or default role");
            response.sendRedirect("/login?error=oidc-unmapped");
            return;
        }
        sessions.establish(principal.get(), request, response);
        attempt.succeeded();
        response.sendRedirect("/");
    }

    private static Set<String> groups(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.toSet());
        }
        return claim instanceof String value ? Set.of(value) : Set.of();
    }

    private static String usernameFor(OidcUser oidcUser) {
        String preferred = oidcUser.getPreferredUsername();
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        String email = oidcUser.getEmail();
        return email != null && !email.isBlank()
                ? email
                : "oidc-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
