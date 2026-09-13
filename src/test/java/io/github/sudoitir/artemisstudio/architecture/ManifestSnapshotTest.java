package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.app.StudioFeatures;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code web/manifest.snapshot.json} — the installed module ids and kinds —
 * so the frontend can assert its feature ids equal the backend's without a running
 * server (ADR-0070). Same pattern as {@code OpenApiSnapshotTest}: the file is
 * regenerated here, and CI's {@code git diff --exit-code} catches an uncommitted
 * change.
 */
class ManifestSnapshotTest {

    private static final Path SNAPSHOT = Path.of("web", "manifest.snapshot.json");

    @Test
    void manifestSnapshotIsCurrent() throws Exception {
        var modules = StudioFeatures.descriptors().stream()
                .sorted(Comparator.comparing(FeatureDescriptor::id))
                .map(d -> Map.of("id", d.id(), "kind", d.kind().name(), "required", d.required()))
                .toList();
        String pretty = JsonMapper.builder()
                        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .build()
                        .writeValueAsString(Map.of("modules", modules))
                + "\n";
        String existing = Files.exists(SNAPSHOT) ? Files.readString(SNAPSHOT, StandardCharsets.UTF_8) : null;
        if (!pretty.equals(existing)) {
            Files.writeString(SNAPSHOT, pretty, StandardCharsets.UTF_8);
        }
    }
}
