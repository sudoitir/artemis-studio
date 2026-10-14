package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;

/** The audit trail every mutation writes. Module descriptor (ADR-0070). */
public final class AuditModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("audit")
            .title("Audit log")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .permission(PermissionDef.cluster(AuditPermissions.EXPORT, "Export the audit trail"))
            .build();

    private AuditModule() {}
}
