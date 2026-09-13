package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Jolokia and Core transport, the per-node rate limit, capabilities. Module descriptor (ADR-0070). */
public final class BrokerModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("broker")
            .title("Broker connectivity")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .build();

    private BrokerModule() {}
}
