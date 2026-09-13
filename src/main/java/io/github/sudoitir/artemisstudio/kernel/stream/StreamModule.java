package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** The per-cluster server-sent event stream. Module descriptor (ADR-0070). */
public final class StreamModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("stream")
            .title("Event stream")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .build();

    private StreamModule() {}
}
