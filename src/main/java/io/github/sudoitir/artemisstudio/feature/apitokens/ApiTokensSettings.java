package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import org.springframework.stereotype.Component;

/** The token lifetime policy and request limits (ADR-0136). Read on each use, so a change applies at once. */
@Component
public class ApiTokensSettings implements SettingsContribution {

    public static final String MAX_LIFETIME = "apitokens.max-lifetime";
    public static final String ROTATION_OVERLAP = "apitokens.rotation-overlap";
    public static final String STALE_AFTER = "apitokens.stale-after";
    public static final String TOKEN_REQUESTS_PER_MINUTE = "apitokens.token-requests-per-minute";
    public static final String TOKEN_CONCURRENCY = "apitokens.token-concurrency";
    public static final String USER_REQUESTS_PER_MINUTE = "apitokens.user-requests-per-minute";

    private static final String LIFETIME = "API token lifetime";
    private static final String LIMITS = "API token limits";

    @Override
    public String featureId() {
        return "apitokens";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        MAX_LIFETIME,
                        LIFETIME,
                        "Maximum lifetime",
                        "No token lives longer than this from its creation. Lowering it shortens existing tokens at once.",
                        Kind.DURATION,
                        () -> "90d",
                        null),
                new SettingDef(
                        ROTATION_OVERLAP,
                        LIFETIME,
                        "Rotation overlap",
                        "How long the old secret keeps working after a token is rotated.",
                        Kind.DURATION,
                        () -> "24h",
                        null),
                new SettingDef(
                        STALE_AFTER,
                        LIFETIME,
                        "Flag as stale after",
                        "Tokens unused for this long are flagged in the administrators' inventory.",
                        Kind.DURATION,
                        () -> "30d",
                        null),
                new SettingDef(
                        TOKEN_REQUESTS_PER_MINUTE,
                        LIMITS,
                        "Requests per token per minute",
                        "Further requests with the token are answered 429 until the minute ends.",
                        Kind.INT,
                        () -> "600",
                        null),
                new SettingDef(
                        TOKEN_CONCURRENCY,
                        LIMITS,
                        "Concurrent requests per token",
                        "Requests with one token that may be in flight at once.",
                        Kind.INT,
                        () -> "8",
                        null),
                new SettingDef(
                        USER_REQUESTS_PER_MINUTE,
                        LIMITS,
                        "Token requests per user per minute",
                        "All of a user's tokens together. Browser sessions are not limited.",
                        Kind.INT,
                        () -> "1200",
                        null));
    }
}
