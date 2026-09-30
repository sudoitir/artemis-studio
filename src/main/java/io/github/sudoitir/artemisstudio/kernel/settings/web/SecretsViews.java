package io.github.sudoitir.artemisstudio.kernel.settings.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.security.SecretRotations;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The secret provider and key rotation API. Versions and counts only; never a key. */
public final class SecretsViews {

    private SecretsViews() {}

    /**
     * @param countsByVersion how many stored secrets each key version protects
     * @param lastRotation the most recent rotation, absent before the first
     */
    public record SecretsStatus(
            @Schema(requiredMode = REQUIRED) String provider,
            @Schema(requiredMode = REQUIRED) int currentVersion,
            @Schema(requiredMode = REQUIRED) List<Integer> availableVersions,
            @Schema(requiredMode = REQUIRED) Map<String, Long> countsByVersion,
            RotationView lastRotation) {}

    /**
     * @param status {@code RUNNING}, {@code SUCCEEDED} or {@code FAILED}
     * @param error the store and row a failed rotation stopped at, never a value
     */
    public record RotationView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) int fromVersion,
            @Schema(requiredMode = REQUIRED) int toVersion,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String startedBy,
            @Schema(requiredMode = REQUIRED) Instant startedAt,
            Instant finishedAt,
            @Schema(requiredMode = REQUIRED) long rewrapped,
            @Schema(requiredMode = REQUIRED) long remaining,
            String error) {

        static RotationView of(SecretRotations.Rotation r) {
            return new RotationView(
                    r.id(),
                    r.fromVersion(),
                    r.toVersion(),
                    r.status(),
                    r.startedBy(),
                    r.startedAt(),
                    r.finishedAt(),
                    r.rewrapped(),
                    r.remaining(),
                    r.error());
        }
    }

    static SecretsStatus of(SecretRotations.Status s) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        s.countsByVersion().forEach((version, n) -> counts.put(String.valueOf(version), n));
        return new SecretsStatus(
                s.provider(),
                s.currentVersion(),
                s.availableVersions(),
                counts,
                s.lastRotation().map(RotationView::of).orElse(null));
    }
}
