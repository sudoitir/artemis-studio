package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Personal bearer tokens for automation and MCP. Module descriptor (ADR-0070). */
public final class ApiTokensModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("apitokens")
            .title("API tokens")
            .kind(FeatureDescriptor.Kind.IDENTITY_PROVIDER)
            .permission(new PermissionDef(TokenPermissions.TOKEN_ADMIN, "See and revoke every user's API tokens", true))
            .apiPrefix("/api/v1/tokens")
            .apiPrefix("/api/v1/admin/tokens")
            .settingKey(ApiTokensSettings.MAX_LIFETIME)
            .settingKey(ApiTokensSettings.ROTATION_OVERLAP)
            .settingKey(ApiTokensSettings.STALE_AFTER)
            .settingKey(ApiTokensSettings.TOKEN_REQUESTS_PER_MINUTE)
            .settingKey(ApiTokensSettings.TOKEN_CONCURRENCY)
            .settingKey(ApiTokensSettings.USER_REQUESTS_PER_MINUTE)
            .build();

    private ApiTokensModule() {}
}
