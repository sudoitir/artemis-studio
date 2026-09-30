package io.github.sudoitir.artemisstudio.kernel.lifecycle.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleService.StoreState;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The Data page's API. Durations are in the settings' own syntax ({@code 7d}, {@code 72h}, {@code forever}). */
public final class DataViews {

    private DataViews() {}

    /**
     * One store.
     *
     * @param source {@code core}, or the contributing plugin's id
     * @param defaultRetention the packaged retention, {@code forever} when it keeps everything
     * @param maxRetention the longest allowed retention, {@code forever} when keeping everything is allowed
     * @param quotaUnit {@code BYTES} (quota in MiB) or {@code ROWS} (quota in thousands)
     * @param quota 0 when the store has none
     * @param rows planner estimate; {@code null} when usage could not be read
     * @param quotaUsedPercent {@code null} without a quota
     */
    public record StoreView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String source,
            @Schema(requiredMode = REQUIRED) List<String> tables,
            @Schema(requiredMode = REQUIRED) String retention,
            @Schema(requiredMode = REQUIRED) String defaultRetention,
            @Schema(requiredMode = REQUIRED) String minRetention,
            @Schema(requiredMode = REQUIRED) String maxRetention,
            @Schema(requiredMode = REQUIRED) String quotaUnit,
            @Schema(requiredMode = REQUIRED) int quota,
            @Schema(requiredMode = REQUIRED) int quotaWarnPercent,
            Long rows,
            Long bytes,
            String usageError,
            Integer quotaUsedPercent,
            @Schema(requiredMode = REQUIRED) boolean overWarning,
            Instant lastPurgeAt,
            Long lastPurged,
            String lastPurgeError) {

        static StoreView of(StoreState state) {
            StoreDef def = state.store().def();
            return new StoreView(
                    state.store().id(),
                    def.label(),
                    state.store().source(),
                    def.tables(),
                    state.retention(),
                    def.defaultRetention() == null ? SettingDef.FOREVER : format(def.defaultRetention()),
                    format(def.minRetention()),
                    def.maxRetention() == null ? SettingDef.FOREVER : format(def.maxRetention()),
                    def.quotaUnit().name(),
                    state.quota(),
                    state.quotaWarnPercent(),
                    state.usage() == null ? null : state.usage().rows(),
                    state.usage() == null ? null : state.usage().bytes(),
                    state.usageError(),
                    state.quotaUsedPercent(),
                    state.overWarning(),
                    state.lastPurge() == null ? null : state.lastPurge().at(),
                    state.lastPurge() == null ? null : state.lastPurge().purged(),
                    state.lastPurge() == null ? null : state.lastPurge().error());
        }

        private static String format(Duration d) {
            if (d.toSeconds() % 86_400 == 0) {
                return d.toDays() + "d";
            }
            if (d.toSeconds() % 3_600 == 0) {
                return d.toHours() + "h";
            }
            return d.toMinutes() + "m";
        }
    }

    public record StoresResponse(
            @Schema(requiredMode = REQUIRED) List<StoreView> stores) {}

    public record UpdatePolicyRequest(
            @NotBlank String retention,
            @Min(0) int quota,
            @Min(1) @Max(100) int quotaWarnPercent) {}

    public record PreviewRequest(@NotBlank String retention) {}

    public record PreviewResponse(
            @Schema(requiredMode = REQUIRED) long rows,
            @Schema(requiredMode = REQUIRED) long bytes) {
        static PreviewResponse of(PurgeEstimate estimate) {
            return new PreviewResponse(estimate.rows(), estimate.bytes());
        }
    }
}
