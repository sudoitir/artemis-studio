package io.github.sudoitir.artemisstudio.security.oidc;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.security.GrantLoader;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JIT-provisions an {@code app_user} row for a first-time OIDC identity and
 * re-applies its role mapping on every login (oidc-sso spec). Deliberately not
 * an {@code OAuth2UserService} — see design.md's revision of decision 1's
 * approach: everything downstream (audit, {@code @PreAuthorize}) expects a
 * {@link StudioPrincipal}, so provisioning happens in a plain service called
 * from {@code OidcAuthenticationSuccessHandler} once Spring Security has
 * already completed the OIDC exchange, rather than inside the OIDC user-loading
 * hook where only an {@link OidcUser} is available.
 */
@Component
@RequiredArgsConstructor
public class StudioOidcUserService {

    private final AppUserRepository users;
    private final GrantLoader grantLoader;
    private final ClaimRoleMapper claimRoleMapper;
    private final AuditService audit;

    public sealed interface Result {
        record Authenticated(StudioPrincipal principal) implements Result {}

        record Refused(String username) implements Result {}
    }

    @Transactional
    public Result provisionAndAuthenticate(OidcUser oidcUser, String issuer) {
        String subject = oidcUser.getSubject();
        AppUserEntity user = users.findByIssuerAndSubject(issuer, subject).orElse(null);
        boolean firstLogin = user == null;
        if (firstLogin) {
            String username = usernameFor(oidcUser);
            user = users.save(AppUserEntity.oidc(username, oidcUser.getEmail(), issuer, subject));
        }

        ClaimRoleMapper.Outcome outcome = claimRoleMapper.apply(user.getId(), oidcUser.getClaims());
        if (outcome == ClaimRoleMapper.Outcome.REFUSED) {
            audit.succeed(
                    audit.begin(
                            new Actor(Actor.ANONYMOUS, null, null, null),
                            "LOGIN",
                            "user",
                            user.getUsername(),
                            null,
                            null,
                            Map.of("reason", "oidc-unmapped"),
                            false),
                    0);
            return new Result.Refused(user.getUsername());
        }

        StudioPrincipal principal =
                new StudioPrincipal(user.getId(), user.getUsername(), grantLoader.loadFor(user.getId()), false);
        var event = audit.begin(
                new Actor(user.getUsername(), null, null, user.getId()),
                "LOGIN",
                "user",
                user.getUsername(),
                null,
                null,
                Map.of("via", "oidc"),
                false);
        audit.succeed(event, 1);
        return new Result.Authenticated(principal);
    }

    private static String usernameFor(OidcUser oidcUser) {
        String preferred = oidcUser.getPreferredUsername();
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        String email = oidcUser.getEmail();
        return email != null && !email.isBlank() ? email : "oidc-" + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
