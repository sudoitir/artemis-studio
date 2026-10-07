package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import java.util.Set;

/**
 * What a provider is asked to decide on.
 *
 * @param mode whether this is a preview or a submission
 * @param type the operation type
 * @param typeVersion its {@link GatedOperation#version()}
 * @param params the canonical parameters with {@link GatedOperation#redactedPaths()} redacted
 * @param paramsHash the hex SHA-256 binding the exact request ({@link CanonicalJson#hash})
 * @param reason the requester's reason, or {@code null}
 */
@PluginApi
public record GateRequest(
        Mode mode,
        String type,
        int typeVersion,
        Set<Trait> traits,
        ExecutionMode executionMode,
        OperationScope scope,
        String summary,
        List<DisplayRow> display,
        String params,
        String paramsHash,
        Effect effect,
        Requester requester,
        String reason) {

    public GateRequest {
        traits = Set.copyOf(traits);
        display = List.copyOf(display);
    }

    public enum Mode {
        /** A screen asks what would happen; nothing is held or run. */
        PREVIEW,
        /** The operation was submitted; a hold stores it. */
        SUBMIT
    }
}
