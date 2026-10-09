package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.gate.GatePreview;
import java.util.Map;

/**
 * What applying a change set would do now.
 *
 * @param outcome whether it would run, be held for approval or be denied; {@code null} when a value is invalid
 * @param policyLabel the name of the policy that decided, or {@code null}
 * @param denyReason why it would be denied, or {@code null}
 * @param fieldErrors the reason for each invalid setting, by key; empty when all are valid
 */
public record SettingsChangePreview(
        GatePreview.Outcome outcome, String policyLabel, String denyReason, Map<String, String> fieldErrors) {}
