package io.github.sudoitir.artemisstudio.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** An identity provider's group -> role mappings and default role (ADR-0073). */
public final class GroupMappingViews {

    private GroupMappingViews() {}

    public record GroupMappingRequest(
            @NotBlank String groupName,
            @NotNull @Schema(requiredMode = REQUIRED) UUID roleId,
            @NotBlank @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId) {}

    public record GroupMappingView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String groupName,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String roleName,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId) {}

    public record GroupMappingsView(
            @Schema(requiredMode = REQUIRED, nullable = true)
            UUID defaultRoleId,

            @Schema(requiredMode = REQUIRED) List<GroupMappingView> mappings) {}

    /** A {@code null} role clears the default, so unmapped users are refused. */
    public record DefaultRoleRequest(
            @Schema(nullable = true) UUID roleId) {}
}
