package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** The audit trail every mutation writes. Module descriptor (ADR-0070). */
public final class AuditModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("audit")
            .title("Audit log")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .build();

    private AuditModule() {}
}
