package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/** The login/logout/me/password-change API (identity-and-sessions spec, ADR-0037). */
public final class AuthViews {

    private AuthViews() {}

    public record LoginRequest(
            @NotBlank String username, @NotBlank String password) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record GrantView(
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId,
            @Schema(requiredMode = REQUIRED) List<String> permissions) {}

    public record MeView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) boolean mustChangePassword,
            @Schema(requiredMode = REQUIRED) List<GrantView> grants) {}

    public record ProviderView(
            @Schema(requiredMode = REQUIRED) String registrationId,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String authorizationUrl) {}
}
