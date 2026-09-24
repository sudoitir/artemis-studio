package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Setup review cadence, tunable at runtime (ADR-0047, ADR-0106). */
@Component
@RequiredArgsConstructor
public class SetupReviewSettings implements SettingsContribution {

    public static final String INTERVAL = "setupreview.interval";
    public static final String MIN_INTERVAL = "setupreview.min-interval";

    private final SetupReviewProperties defaults;

    @Override
    public String featureId() {
        return "setupreview";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        INTERVAL,
                        "Setup review",
                        "Review interval",
                        "How often every cluster's configuration is reviewed. Each review is one batched read per node.",
                        Kind.DURATION,
                        () -> defaults.interval().toString(),
                        null),
                new SettingDef(
                        MIN_INTERVAL,
                        "Setup review",
                        "Minimum spacing",
                        "A review requested sooner than this after the last one returns the last one instead.",
                        Kind.DURATION,
                        () -> defaults.minInterval().toString(),
                        null));
    }
}
