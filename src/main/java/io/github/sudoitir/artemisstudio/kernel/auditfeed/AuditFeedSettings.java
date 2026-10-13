package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import org.springframework.stereotype.Component;

/** How long delivery to audit receivers may lag before retention stops waiting and the alert fires (ADR-0190). */
@Component
public class AuditFeedSettings implements SettingsContribution {

    public static final String MAX_UNACKNOWLEDGED_AGE = "audit.delivery.max-unacknowledged-age";
    public static final String TIMEOUT = "audit.delivery.timeout";
    public static final String ALERT_AFTER = "audit.delivery.alert-after";

    private static final String GROUP = "Audit delivery";

    @Override
    public String featureId() {
        return AuditFeedModule.ID;
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        MAX_UNACKNOWLEDGED_AGE,
                        GROUP,
                        "Longest an unacknowledged event is kept",
                        "Retention keeps audit events a receiver has not acknowledged until they are this old, then purges them and records a gap.",
                        Kind.DURATION,
                        () -> "30d",
                        null),
                new SettingDef(
                        TIMEOUT,
                        GROUP,
                        "Receiver timeout",
                        "How long one call to an audit receiver or retention hold may take before it counts as failed.",
                        Kind.DURATION,
                        () -> "30s",
                        null),
                new SettingDef(
                        ALERT_AFTER,
                        GROUP,
                        "Delivery alert after",
                        "A receiver whose oldest unacknowledged event is older than this raises an audit delivery alert.",
                        Kind.DURATION,
                        () -> "1h",
                        null));
    }
}
