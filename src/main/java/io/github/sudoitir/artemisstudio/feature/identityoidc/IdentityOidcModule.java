package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** OpenID Connect sign-in with JIT provisioning. Module descriptor (ADR-0070). */
public final class IdentityOidcModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("identity-oidc")
            .title("Single sign-on")
            .kind(FeatureDescriptor.Kind.IDENTITY_PROVIDER)
            .build();

    private IdentityOidcModule() {}
}
