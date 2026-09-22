package io.github.sudoitir.artemisstudio.feature.transfer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Packaged defaults of the transfer settings (transfer design D8); overridden at runtime through {@link TransferSettings}. */
@ConfigurationProperties(prefix = "artemis-studio.transfer")
public record TransferProperties(
        @DefaultValue("200") int batchSize,
        @DefaultValue("1000") int messagesPerSecond,
        @DefaultValue("4") int maxConcurrentRuns,
        @DefaultValue("90") int capacityThresholdPercent,
        @DefaultValue("10m") Duration capacityWait) {}
