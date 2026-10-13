package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Delivery of the audit trail to plugins, and the retention that waits for it. Module descriptor (ADR-0070, ADR-0190). */
public final class AuditFeedModule {

    public static final String ID = "auditfeed";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(ID)
            .title("Audit delivery")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .settingKey(AuditFeedSettings.MAX_UNACKNOWLEDGED_AGE)
            .settingKey(AuditFeedSettings.TIMEOUT)
            .settingKey(AuditFeedSettings.ALERT_AFTER)
            .build();

    private AuditFeedModule() {}
}
