package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Declared broker configuration (ADR-0067). {@code driftInterval} paces the scheduled
 * comparison of every live node against its cluster's declaration — one batched read per
 * node per pass; {@code applyStepCap} is the most management writes one apply may issue
 * before it needs an explicit override, the configuration counterpart of the bulk cap.
 */
@ConfigurationProperties(prefix = "artemis-studio.broker-config")
public record BrokerConfigProperties(
        @DefaultValue("5m") Duration driftInterval,
        @DefaultValue("100") int applyStepCap) {}
