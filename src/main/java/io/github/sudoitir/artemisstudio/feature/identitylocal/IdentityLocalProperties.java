package io.github.sudoitir.artemisstudio.feature.identitylocal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Packaged defaults of the local-account settings (ADR-0144); the minimum length is overridden at
 * runtime through {@link IdentityLocalSettings}.
 *
 * @param trustedDeviceLifetime how long a browser stays trusted after a second factor; {@code 0} turns
 *     trusted devices off (ADR-0143)
 * @param recover break-glass: the username of a local account to recover at startup, or blank (ADR-0143, D10)
 */
@ConfigurationProperties(prefix = "artemis-studio.identity-local")
public record IdentityLocalProperties(
        @DefaultValue("12") int passwordMinLength,
        @DefaultValue("30d") Duration trustedDeviceLifetime,
        @DefaultValue("") String recover) {}
