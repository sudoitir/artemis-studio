package io.github.sudoitir.artemisstudio.platform.broker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker connection defaults.
 *
 * @param connectTimeout {@code RestClient} connect timeout for every Jolokia call (ADR-0010)
 * @param readTimeout {@code RestClient} read timeout for every Jolokia call (ADR-0010)
 * @param clockSkewToleranceMs how far a broker clock may disagree with Studio's before it
 *     is called skew. The floor is set by the measurement, not by taste: Jolokia reports
 *     its timestamp in whole seconds, so a single reading is ±500ms before the network is
 *     counted. Two seconds is the smallest value that is not mostly quantisation (ADR-0053).
 */
@ConfigurationProperties(prefix = "artemis-studio.broker")
public record BrokerProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("2000") long clockSkewToleranceMs) {}
