package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Notification delivery cadence, retry bounds and the SMTP timeout (ADR-0036, ADR-0105). */
@Component
@RequiredArgsConstructor
public class AlertingSettings implements SettingsContribution {

    public static final String DISPATCH_INTERVAL = "alerting.dispatch-interval";
    public static final String MAX_ATTEMPTS = "alerting.max-attempts";
    public static final String INITIAL_BACKOFF = "alerting.initial-backoff";
    public static final String MAX_BACKOFF = "alerting.max-backoff";
    public static final String EMAIL_TIMEOUT = "alerting.email-timeout";

    private final AlertingProperties defaults;

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
                        () -> defaults.dispatchInterval().toString(),
                        null),
                new SettingDef(
                        MAX_ATTEMPTS,
                        "Alerting",
                        "Max delivery attempts",
                        "A delivery is marked dead after this many failures.",
                        Kind.INT,
                        () -> Integer.toString(defaults.maxAttempts()),
                        null),
                new SettingDef(
                        INITIAL_BACKOFF,
                        "Alerting",
                        "Initial retry backoff",
                        "Delay before the first retry. Doubles with jitter up to the ceiling.",
                        Kind.DURATION,
                        () -> defaults.initialBackoff().toString(),
                        null),
                new SettingDef(
                        MAX_BACKOFF,
                        "Alerting",
                        "Max retry backoff",
                        "Ceiling on the exponential retry delay.",
                        Kind.DURATION,
                        () -> defaults.maxBackoff().toString(),
                        null),
                new SettingDef(
                        EMAIL_TIMEOUT,
                        "Alerting",
                        "Email delivery timeout",
                        "Connect, read and write timeout of one SMTP delivery. A slower server is retried.",
                        Kind.DURATION,
                        () -> defaults.emailTimeout().toString(),
                        null));
    }
}
