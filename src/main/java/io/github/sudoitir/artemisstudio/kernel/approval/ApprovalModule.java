package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** The approval gate's engine and held operations. Module descriptor (ADR-0070, ADR-0179). */
public final class ApprovalModule {

    public static final String ID = "approvals";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(ID)
            .title("Approvals")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .apiPrefix("/api/v1/held-operations")
            .apiPrefix("/api/v1/gate")
            .settingKey(ApprovalSettings.DECIDE_TIMEOUT)
            .settingKey(ApprovalSettings.MAX_HOLD)
            .settingKey(ApprovalSettings.RUN_WINDOW)
            .settingKey(ApprovalSettings.MAX_OPEN_PER_REQUESTER)
            .settingKey(ApprovalSettings.RUN_LEASE)
            .build();

    private ApprovalModule() {}
}
