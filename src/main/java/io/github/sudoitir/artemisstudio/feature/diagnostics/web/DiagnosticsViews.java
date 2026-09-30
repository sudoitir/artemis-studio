package io.github.sudoitir.artemisstudio.feature.diagnostics.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Section;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Snapshot;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Summary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shapes of the diagnostics API. */
public final class DiagnosticsViews {

    private DiagnosticsViews() {}

    public record SummaryView(
            @Schema(requiredMode = REQUIRED) String studioVersion,
            @Schema(requiredMode = REQUIRED) int contractVersion,
            @Schema(requiredMode = REQUIRED) String java,
            @Schema(requiredMode = REQUIRED) String os,
            @Schema(requiredMode = REQUIRED) String database) {

        static SummaryView of(Summary s) {
            return new SummaryView(s.studioVersion(), s.contractVersion(), s.java(), s.os(), s.database());
        }
    }

    /** One section exactly as it will be written into the bundle. */
    public record SectionView(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) String fileName,
            @Schema(requiredMode = REQUIRED) String content,
            @Schema(requiredMode = REQUIRED) long bytes,
            @Schema(requiredMode = REQUIRED) int redactions) {

        static SectionView of(Section s) {
            return new SectionView(
                    s.key(),
                    s.title(),
                    s.fileName(),
                    s.content(),
                    s.content().getBytes(StandardCharsets.UTF_8).length,
                    s.redactions());
        }
    }

    public record BundleView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(requiredMode = REQUIRED) List<SectionView> sections) {

        static BundleView of(Snapshot s) {
            return new BundleView(
                    s.id(),
                    s.createdAt(),
                    s.expiresAt(),
                    s.sections().stream().map(SectionView::of).toList());
        }
    }

    /** The section keys to keep, from those the preview showed. */
    public record DownloadRequest(
            @Schema(requiredMode = REQUIRED) @NotEmpty List<String> sections) {}
}
