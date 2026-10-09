package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * What {@link OperationGate#run} would do now, so a screen can offer "Request approval" before
 * anything is submitted.
 *
 * @param outcome what would happen
 * @param policy the policy that decided, or {@code null} when none did
 * @param effect the estimated effect, or {@code null} when no provider is armed
 * @param reason why it would be denied, or {@code null}
 */
@PluginApi
public record GatePreview(Outcome outcome, PolicyRef policy, Effect effect, String reason) {

    public enum Outcome {
        RUN,
        HOLD,
        DENY
    }
}
