package io.github.sudoitir.artemisstudio.feature.identitylocal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Packaged defaults of the local-account settings (ADR-0143); the minimum length is overridden at
 * runtime through {@link IdentityLocalSettings}.
 */
@ConfigurationProperties(prefix = "artemis-studio.identity-local")
public record IdentityLocalProperties(@DefaultValue("12") int passwordMinLength) {}
