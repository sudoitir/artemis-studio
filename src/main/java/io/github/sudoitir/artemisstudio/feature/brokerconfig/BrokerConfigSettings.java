package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Drift evaluation cadence and the apply step cap (ADR-0067). */
@Component
@RequiredArgsConstructor
public class BrokerConfigSettings implements SettingsContribution {

    public static final String DRIFT_INTERVAL = "config.drift-interval";
    public static final String APPLY_STEP_CAP = "config.apply-step-cap";

    private final BrokerConfigProperties defaults;

    @Override
    public String featureId() {
        return "brokerconfig";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        DRIFT_INTERVAL,
                        "Broker configuration",
                        "Drift evaluation interval",
                        "How often every live node is compared against its cluster's declared configuration."
                                + " One batched read per node per pass; nothing is ever applied by it.",
                        Kind.DURATION,
                        () -> defaults.driftInterval().toString(),
                        null),
                new SettingDef(
                        APPLY_STEP_CAP,
                        "Broker configuration",
                        "Apply step cap",
                        "Most management writes one configuration apply may issue before it needs an explicit override.",
                        Kind.INT,
                        () -> Integer.toString(defaults.applyStepCap()),
                        null));
    }
}
