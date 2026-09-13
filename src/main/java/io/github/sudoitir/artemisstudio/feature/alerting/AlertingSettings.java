package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.core.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Notification delivery cadence and retry bounds (ADR-0036). */
@Component
@RequiredArgsConstructor
public class AlertingSettings implements SettingsContribution {

    public static final String DISPATCH_INTERVAL = "alerting.dispatch-interval";
    public static final String MAX_ATTEMPTS = "alerting.max-attempts";
    public static final String INITIAL_BACKOFF = "alerting.initial-backoff";
    public static final String MAX_BACKOFF = "alerting.max-backoff";

    private final ArtemisStudioProperties defaults;

    @Override
    public String featureId() {
        return "alerting";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        DISPATCH_INTERVAL,
                        "Alerting",
                        "Dispatch interval",
                        "How often queued notification deliveries are claimed and attempted.",
                        Kind.DURATION,
                        () -> defaults.alerting().dispatchInterval().toString(),
                        null),
                new SettingDef(
                        MAX_ATTEMPTS,
                        "Alerting",
                        "Max delivery attempts",
                        "A delivery is marked dead after this many failures.",
                        Kind.INT,
                        () -> Integer.toString(defaults.alerting().maxAttempts()),
                        null),
                new SettingDef(
                        INITIAL_BACKOFF,
                        "Alerting",
                        "Initial retry backoff",
                        "Delay before the first retry. Doubles with jitter up to the ceiling.",
                        Kind.DURATION,
                        () -> defaults.alerting().initialBackoff().toString(),
                        null),
                new SettingDef(
                        MAX_BACKOFF,
                        "Alerting",
                        "Max retry backoff",
                        "Ceiling on the exponential retry delay.",
                        Kind.DURATION,
                        () -> defaults.alerting().maxBackoff().toString(),
                        null));
    }
}
