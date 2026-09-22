package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** How fast and how much a transfer run moves, and how long it waits for a full target (transfer design D8). */
@Component
@RequiredArgsConstructor
public class TransferSettings implements SettingsContribution {

    public static final String BATCH_SIZE = "transfer.batch-size";
    public static final String MESSAGES_PER_SECOND = "transfer.messages-per-second";
    public static final String MAX_CONCURRENT_RUNS = "transfer.max-concurrent-runs";
    public static final String CAPACITY_THRESHOLD_PERCENT = "transfer.capacity-threshold-percent";
    public static final String CAPACITY_WAIT = "transfer.capacity-wait";

    private static final String GROUP = "Message transfer";

    private final TransferProperties defaults;

    @Override
    public String featureId() {
        return "transfer";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        BATCH_SIZE,
                        GROUP,
                        "Batch size",
                        "Messages relayed per transaction. A move parks at most twice this many outside the source"
                                + " queue at any moment.",
                        Kind.INT,
                        () -> Integer.toString(defaults.batchSize()),
                        null),
                new SettingDef(
                        MESSAGES_PER_SECOND,
                        GROUP,
                        "Messages per second",
                        "Most messages one run relays per second.",
                        Kind.INT,
                        () -> Integer.toString(defaults.messagesPerSecond()),
                        null),
                new SettingDef(
                        MAX_CONCURRENT_RUNS,
                        GROUP,
                        "Concurrent runs",
                        "Most transfer runs executing at once across every cluster.",
                        Kind.INT,
                        () -> Integer.toString(defaults.maxConcurrentRuns()),
                        null),
                new SettingDef(
                        CAPACITY_THRESHOLD_PERCENT,
                        GROUP,
                        "Capacity threshold (%)",
                        "A run waits when the target address or disk is at least this full, and continues by itself"
                                + " once it is not.",
                        Kind.INT,
                        () -> Integer.toString(defaults.capacityThresholdPercent()),
                        null),
                new SettingDef(
                        CAPACITY_WAIT,
                        GROUP,
                        "Capacity wait",
                        "How long a run waits for a full target before it stops. A stopped run can be resumed.",
                        Kind.DURATION,
                        () -> defaults.capacityWait().toString(),
                        null));
    }
}
