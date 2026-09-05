package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Personal API tokens (api-tokens spec, ADR-0039). */
public final class TokenViews {

    private TokenViews() {}

    public record TokenGrantRequest(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId) {}

    public record CreateTokenRequest(
            @NotBlank String name,
            @Schema(nullable = true) Instant expiresAt,
            @Schema(requiredMode = REQUIRED) List<TokenGrantRequest> grants) {}

    public record TokenView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String prefix,
            @Schema(nullable = true) Instant expiresAt,
            @Schema(nullable = true) Instant lastUsedAt,
            @Schema(nullable = true) Instant revokedAt,
            @Schema(requiredMode = REQUIRED) Instant createdAt) {}

    public record CreatedTokenView(
            @Schema(requiredMode = REQUIRED) TokenView token,
            @Schema(requiredMode = REQUIRED) String value) {}
}
