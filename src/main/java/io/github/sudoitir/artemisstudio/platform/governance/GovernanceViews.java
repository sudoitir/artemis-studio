package io.github.sudoitir.artemisstudio.platform.governance;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * How governed content describes itself on the wire (data-governance spec). Carried by every view
 * that holds message content, so a client never has to guess whether a string is a real value.
 */
public final class GovernanceViews {

    private GovernanceViews() {}

    /**
     * One sensitive value.
     *
     * @param path property or header name, or JSON body path; empty for a plain-text body
     * @param label the class in words, for display
     * @param clear the value is shown in clear because the caller holds {@code message:clear}
     */
    public record RedactionView(
            @Schema(requiredMode = REQUIRED) String location,
            @Schema(requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) String dataClass,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) boolean clear) {}

    /** Content not shown, why, and the setting that changes it where one does. */
    public record WithheldView(
            @Schema(requiredMode = REQUIRED) String location,
            @Schema(requiredMode = REQUIRED) String reason,
            @Schema(nullable = true) String settingKey) {}

    public static List<RedactionView> redactions(GovernedMessage message) {
        return message.redactions().stream()
                .map(r -> new RedactionView(
                        r.location().name(),
                        r.path(),
                        r.dataClass().name(),
                        r.dataClass().label(),
                        r.action().name(),
                        r.clear()))
                .toList();
    }

    public static List<WithheldView> withheld(GovernedMessage message) {
        return message.withheld().stream()
                .map(w -> new WithheldView(w.location().name(), w.reason(), w.settingKey()))
                .toList();
    }
}
