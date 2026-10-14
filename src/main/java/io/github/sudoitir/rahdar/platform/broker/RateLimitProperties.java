package io.github.sudoitir.rahdar.platform.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The per-node management call ceiling enforced by {@link NodeCallLimiter}. */
@ConfigurationProperties(prefix = "rahdar.rate-limit")
public record RateLimitProperties(@DefaultValue("20") int managementCallsPerSecond) {}
