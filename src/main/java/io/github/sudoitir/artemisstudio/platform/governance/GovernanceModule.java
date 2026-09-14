package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Data governance: the content policy every message path goes through. Module descriptor (ADR-0070, ADR-0075). */
public final class GovernanceModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("governance")
            .title("Data governance")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .permission(new PermissionDef(
                    GovernancePermissions.MESSAGE_CLEAR, "See sensitive message values in clear (never credentials)"))
            .permission(new PermissionDef(
                    GovernancePermissions.GOVERNANCE_READ, "View masking rules and the classification inbox"))
            .permission(new PermissionDef(
                    GovernancePermissions.GOVERNANCE_WRITE, "Change masking rules and confirm or dismiss findings"))
            .settingKey(GovernanceSettings.SCAN_LIMIT)
            .apiPrefix("/api/v1/governance")
            .build();

    private GovernanceModule() {}
}
