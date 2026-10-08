package io.github.sudoitir.artemisstudio.kernel.gate;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of a {@code 202} that held an operation for approval instead of running it, whichever endpoint reached
 * the gate. Every module declares it through {@link HeldResponse}.
 */
public record HeldOutcome(
        @Schema(requiredMode = REQUIRED, allowableValues = "held")
        String outcome,

        @Schema(requiredMode = REQUIRED) HeldRef heldOperation) {

    public static HeldOutcome of(OperationHeldException held) {
        return new HeldOutcome("held", new HeldRef(held.heldId(), held.summary(), held.expiresAt(), held.link()));
    }

    public record HeldRef(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String summary,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,

            @Schema(requiredMode = REQUIRED, description = "The held operation in this API.")
            String link) {}
}
