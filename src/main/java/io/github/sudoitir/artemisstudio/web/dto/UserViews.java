package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
            @Schema(requiredMode = REQUIRED) String authSource,
            @Schema(requiredMode = REQUIRED) boolean disabled,
            @Schema(requiredMode = REQUIRED) boolean mustChangePassword,
            @Schema(requiredMode = REQUIRED) List<GrantSummary> grants) {}

    public record RoleRequest(
            @NotBlank String name,
            @Schema(requiredMode = REQUIRED) List<String> permissions) {}

    public record RoleView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) boolean builtin,
            @Schema(requiredMode = REQUIRED) List<String> permissions) {}

    public record PermissionView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String label) {}
}
