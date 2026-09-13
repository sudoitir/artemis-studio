package io.github.sudoitir.artemisstudio.platform.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The per-node management call ceiling enforced by {@link NodeCallLimiter}. */
@ConfigurationProperties(prefix = "artemis-studio.rate-limit")
public record RateLimitProperties(@DefaultValue("20") int managementCallsPerSecond) {}
