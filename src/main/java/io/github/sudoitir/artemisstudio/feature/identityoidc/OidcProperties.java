package io.github.sudoitir.artemisstudio.feature.identityoidc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OIDC claim mapping (ADR-0040).
 *
 * @param oidcClaim the token or userinfo claim whose values are mapped to roles
 * @param oidcDefaultRole the role granted to a login that matches no mapping; {@code null}
 *     refuses such a login
 */
@ConfigurationProperties(prefix = "artemis-studio.security")
public record OidcProperties(@DefaultValue("groups") String oidcClaim, String oidcDefaultRole) {}
