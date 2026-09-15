package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Flow sampling cadence, per-node row cap and observation lease (ADR-0081). */
@Component
@RequiredArgsConstructor
public class FlowSettings implements SettingsContribution {

    public static final String SAMPLE_INTERVAL = "flow.sample-interval";
    public static final String MAX_ROWS_PER_NODE = "flow.max-rows-per-node";
    public static final String DEMAND_LEASE = "flow.demand-lease";

    private final FlowProperties defaults;

    @Override
    public String featureId() {
        return "flow";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        SAMPLE_INTERVAL,
                        "Flow",
                        "Sampling interval",
                        "Time between producer and consumer reads while a cluster's flow is open. Never below 10s.",
                        Kind.DURATION,
                        () -> defaults.sampleInterval().toString(),
                        null),
                new SettingDef(
                        MAX_ROWS_PER_NODE,
                        "Flow",
                        "Rows read per node",
                        "The most producers, and the most consumers, read from one node per sweep. The view states when a node has more.",
                        Kind.INT,
                        () -> Integer.toString(defaults.maxRowsPerNode()),
                        null),
                new SettingDef(
                        DEMAND_LEASE,
                        "Flow",
                        "Observation lease",
                        "How long sampling continues after the last flow view on a cluster closes.",
                        Kind.DURATION,
                        () -> defaults.demandLease().toString(),
                        null));
    }

    /** The sampling interval in effect, floored at {@link FlowProperties#MIN_SAMPLE_INTERVAL}. */
    static Duration sampleInterval(SettingsService settings) {
        Duration configured = settings.duration(SAMPLE_INTERVAL);
        return configured.compareTo(FlowProperties.MIN_SAMPLE_INTERVAL) < 0
                ? FlowProperties.MIN_SAMPLE_INTERVAL
                : configured;
    }
}
