package io.github.sudoitir.artemisstudio.kernel.security;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A team by id and name: who owns a queue or address, as the console shows it. */
@Schema(name = "TeamRef", description = "A team, by id and name.")
public record TeamRef(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) String name) {}
