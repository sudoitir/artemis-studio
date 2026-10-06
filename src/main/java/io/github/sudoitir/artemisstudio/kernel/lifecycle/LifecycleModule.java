package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Retention, quotas and storage health for every store. Module descriptor (ADR-0070, ADR-0134). */
public final class LifecycleModule {

    public static final String ID = "lifecycle";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(ID)
            .title("Data lifecycle")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .apiPrefix("/api/v1/data")
            .permission(
                    PermissionDef.global(DataPermissions.DATA_READ, "View data retention, quotas and storage health"))
            .permission(PermissionDef.global(DataPermissions.DATA_WRITE, "Change data retention and quotas"))
            .settingKey(LifecycleSettings.HOUSEKEEPING_CRON)
            .settingKey(LifecycleSettings.STORAGE_SAMPLE_CRON)
            .build();

    private LifecycleModule() {}
}
