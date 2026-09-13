package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Declared configuration, apply, drift and config diff. Module descriptor (ADR-0070). */
public final class BrokerConfigModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("brokerconfig")
            .title("Broker configuration")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("queues")
            .require("routing")
            .permission(new PermissionDef(
                    BrokerConfigPermissions.CONFIG_WRITE, "Edit a cluster's declared broker configuration"))
            .permission(new PermissionDef(
                    BrokerConfigPermissions.CONFIG_APPLY, "Apply declared broker configuration to brokers"))
            .apiPrefix("/api/v1/clusters/{clusterId}/config")
            .apiPrefix("/api/v1/clusters/{clusterId}/config-diff")
            .settingKey(BrokerConfigSettings.DRIFT_INTERVAL)
            .settingKey(BrokerConfigSettings.APPLY_STEP_CAP)
            .build();

    private BrokerConfigModule() {}
}
