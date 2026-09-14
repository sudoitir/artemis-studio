package io.github.sudoitir.artemisstudio.platform.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-enforced ceiling on a single destructive message operation (ADR-0022). A dry-run
 * count above this is a {@code 422} unless the caller passes {@code ?override=true} behind
 * the UI's typed confirmation.
 */
@ConfigurationProperties(prefix = "artemis-studio.safety")
public record SafetyProperties(@DefaultValue("1000") int bulkCap) {}
