package io.github.sudoitir.artemisstudio.feature.diagnostics;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Support bundle and bug-report summary. Module descriptor (ADR-0070, ADR-0146). */
public final class DiagnosticsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("diagnostics")
            .title("Diagnostics")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(PermissionDef.global(DiagnosticsPermissions.BUNDLE, "Create support bundles"))
            .apiPrefix("/api/v1/diagnostics")
            .apiPrefix("/api/v1/admin/diagnostics")
            .build();

    private DiagnosticsModule() {}
}
