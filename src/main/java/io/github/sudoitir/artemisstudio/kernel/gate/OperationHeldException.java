package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.UUID;

/** The operation was not run but held for approval. Mapped to HTTP 202 with {@link GateContext#HELD_HEADER}. */
@PluginApi
public class OperationHeldException extends RuntimeException {

    private final UUID heldId;
    private final String summary;
    private final Instant expiresAt;

    public OperationHeldException(UUID heldId, String summary, Instant expiresAt) {
        super("Held for approval: " + summary);
        this.heldId = heldId;
        this.summary = summary;
        this.expiresAt = expiresAt;
    }

    public UUID heldId() {
        return heldId;
    }

    public String summary() {
        return summary;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** The API path of the held operation. */
    public String link() {
        return "/api/v1/held-operations/" + heldId;
    }
}
