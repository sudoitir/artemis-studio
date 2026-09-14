package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Personal bearer tokens for automation and MCP. Module descriptor (ADR-0070). */
public final class ApiTokensModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("apitokens")
            .title("API tokens")
            .kind(FeatureDescriptor.Kind.IDENTITY_PROVIDER)
            .permission(new PermissionDef(TokenPermissions.TOKEN_ADMIN, "Manage API tokens"))
            .apiPrefix("/api/v1/tokens")
            .build();

    private ApiTokensModule() {}
}
