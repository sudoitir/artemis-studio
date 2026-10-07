package io.github.sudoitir.artemisstudio.kernel.settings.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.gate.GatePreview;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingChange;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsChangePreview;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The settings API. Values are strings; the service parses and validates per key. */
public final class SettingsViews {

    private SettingsViews() {}

    /**
     * One tunable, described well enough that a client can render an editor for it
     * without knowing the key. {@code group}, {@code label}, {@code hint} and
     * {@code kind} come from the {@code SettingsService} registry, so adding a
     * setting server-side adds it to the Settings screen with no frontend change
     * (ADR-0047).
     *
     * @param value the effective value (override if present, else the default)
     * @param overridden whether a {@code studio_setting} row is in effect
     * @param defaultValue the packaged default, for "reset" affordances
     * @param group the section to render this under
     * @param label the human name of the setting
     * @param hint one line on what it does and what changing it costs
     * @param kind {@code DURATION}, {@code DURATION_OR_OFF} (a duration, or 0 for off), {@code INT} or {@code CRON} — how to validate and edit it
     * @param category the id of the module that owns the setting
     * @param categoryTitle that module's title, for people
     * @param pending the changes to this setting waiting for approval, newest first
     */
    public record SettingValue(
            @Schema(requiredMode = REQUIRED) String value,
            @Schema(requiredMode = REQUIRED) boolean overridden,
            @Schema(requiredMode = REQUIRED) String defaultValue,
            @Schema(requiredMode = REQUIRED) String group,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String hint,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) String category,
            @Schema(requiredMode = REQUIRED) String categoryTitle,
            @Schema(requiredMode = REQUIRED) List<PendingChange> pending) {}

    /**
     * A change to a setting that is held for approval.
     *
     * @param heldId the held operation, whose page is {@code /approvals/<heldId>}
     * @param value the value it would set, or {@code null} when it resets the setting
     * @param reset whether it would reset the setting to its default
     * @param requester who asked for it
     * @param requestedAt when
     */
    public record PendingChange(
            @Schema(requiredMode = REQUIRED) UUID heldId,
            String value,
            @Schema(requiredMode = REQUIRED) boolean reset,
            @Schema(requiredMode = REQUIRED) String requester,
            @Schema(requiredMode = REQUIRED) Instant requestedAt) {}

    public record SettingsResponse(
            @Schema(requiredMode = REQUIRED) Map<String, SettingValue> settings) {}

    /**
     * One change of a change set.
     *
     * @param value the new value; required unless {@code reset}
     * @param reset restore the packaged default instead of setting a value
     */
    public record SettingChangeRequest(@NotBlank String key, String value, Boolean reset) {

        public SettingChange toChange() {
            boolean resetting = Boolean.TRUE.equals(reset);
            if (resetting == (value != null)) {
                throw new IllegalArgumentException(
                        "The change to " + key + " needs either a value or reset, not both and not neither.");
            }
            return resetting ? SettingChange.reset(key) : SettingChange.set(key, value);
        }
    }

    /** Changes and resets applied together or not at all. */
    public record ChangeSetRequest(@NotEmpty List<@Valid SettingChangeRequest> changes) {

        public List<SettingChange> toChanges() {
            return changes.stream().map(SettingChangeRequest::toChange).toList();
        }
    }

    /**
     * What applying a change set would do now.
     *
     * @param outcome {@code RUN}, {@code HOLD} (needs approval) or {@code DENY}; absent when a value is invalid
     * @param reasonRequired whether the request must carry a reason when it is held
     * @param policyLabel the approval policy that decided, when one did
     * @param denyReason why it would be denied
     * @param fieldErrors the reason for each invalid setting, by key
     */
    public record ChangePreview(
            GatePreview.Outcome outcome,
            @Schema(requiredMode = REQUIRED) boolean reasonRequired,
            String policyLabel,
            String denyReason,
            @Schema(requiredMode = REQUIRED) Map<String, String> fieldErrors) {

        public static ChangePreview of(SettingsChangePreview preview) {
            return new ChangePreview(
                    preview.outcome(),
                    preview.reasonRequired(),
                    preview.policyLabel(),
                    preview.denyReason(),
                    preview.fieldErrors());
        }
    }
}
