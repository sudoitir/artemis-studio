package io.github.sudoitir.artemisstudio.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** User, role, and permission-grant administration (authorization spec, ADR-0038). */
public final class UserViews {

    private UserViews() {}

    public record CreateUserRequest(
            @NotBlank String username,
            String email,
            @NotBlank String password) {}

    public record SetDisabledRequest(boolean disabled) {}

    public record GrantRequest(
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId) {}

    public record GrantSummary(
            @Schema(requiredMode = REQUIRED) String roleName,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId) {}

    public record UserView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(nullable = true) String email,
            @Schema(requiredMode = REQUIRED) String providerId,
            @Schema(requiredMode = REQUIRED) boolean disabled,
            @Schema(requiredMode = REQUIRED) boolean mustChangePassword,

            @Schema(
                    nullable = true,
                    description =
                            "When repeated failed sign-ins stop blocking this account; null when it is not locked.")
            Instant lockedUntil,

            @Schema(requiredMode = REQUIRED) List<GrantSummary> grants) {}

    public record RoleRequest(
            @NotBlank String name,
            @Schema(requiredMode = REQUIRED) List<String> permissions,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether local accounts holding this role need a second factor. "
                            + "Single sign-on users rely on their identity provider's own MFA.")
            boolean requiresMfa) {}

    public record RoleView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) boolean builtin,
            @Schema(requiredMode = REQUIRED) List<String> permissions,
            @Schema(requiredMode = REQUIRED) boolean requiresMfa) {}

    public record PermissionView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String featureId,
            @Schema(requiredMode = REQUIRED) String featureTitle,
            @Schema(requiredMode = REQUIRED) boolean globalOnly) {}

    /**
     * One permission a user holds at one grant scope, through one role. {@code via} is the stored
     * permission it came through (itself, or the wildcard that expanded to it). {@code effective}
     * is false, with {@code reason}, when it grants nothing at that scope.
     */
    public record EffectivePermissionView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String roleName,
            @Schema(requiredMode = REQUIRED) String via,
            @Schema(requiredMode = REQUIRED) boolean effective,
            @Schema(nullable = true) String reason) {}
}
