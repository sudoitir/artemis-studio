package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Local username and password login. Module descriptor (ADR-0070). */
public final class IdentityLocalModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("identity-local")
            .title("Password login")
            .kind(FeatureDescriptor.Kind.IDENTITY_PROVIDER)
            .build();

    private IdentityLocalModule() {}
}
