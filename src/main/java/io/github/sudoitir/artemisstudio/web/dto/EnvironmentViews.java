package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Environment grouping CRUD (environments spec). */
public final class EnvironmentViews {

    private EnvironmentViews() {}

    public record EnvironmentRequest(
            @NotBlank String name,
            @Schema(nullable = true) String colour,
            @Schema(requiredMode = REQUIRED) int sortOrder) {}

    public record EnvironmentView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String colour,
            @Schema(requiredMode = REQUIRED) int sortOrder) {}
}
