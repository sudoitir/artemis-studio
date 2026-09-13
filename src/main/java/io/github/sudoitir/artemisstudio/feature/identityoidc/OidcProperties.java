package io.github.sudoitir.artemisstudio.feature.identityoidc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where OIDC groups come from. The mappings and the default role are managed per provider in
 * Studio (ADR-0073).
 *
 * @param oidcClaim the token or userinfo claim whose values are the user's groups
 */
@ConfigurationProperties(prefix = "artemis-studio.security")
public record OidcProperties(@DefaultValue("groups") String oidcClaim) {}
