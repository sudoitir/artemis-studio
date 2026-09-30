package io.github.sudoitir.artemisstudio.feature.diagnostics;

import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Section;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Snapshot;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The prepared support bundles, shared by every replica: the preview and the download of one
 * bundle can reach different replicas (ADR-0152). Expired rows are purged by the data lifecycle
 * (ADR-0134).
 */
@Component
@RequiredArgsConstructor
class DiagnosticsSnapshots implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "diagnostics_snapshot";
    private static final String EXPIRED = "expires_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "diagnostics-snapshots",
            "Support bundle previews",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofHours(1),
            Duration.ofMinutes(1),
            Duration.ofDays(1));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    void save(Snapshot snapshot) {
        jdbc.update(
                "INSERT INTO " + TABLE + " (created_at, expires_at, owner, sections, id) VALUES (?, ?, ?, ?, ?)",
                Timestamp.from(snapshot.createdAt()),
                Timestamp.from(snapshot.expiresAt()),
                snapshot.owner(),
                mapper.writeValueAsString(snapshot.sections()),
                snapshot.id());
    }

    /** The snapshot, unless it is unknown or has expired. */
    Optional<Snapshot> find(UUID id) {
        return jdbc
                .query(
                        "SELECT created_at, expires_at, owner, sections FROM " + TABLE
                                + " WHERE id = ? AND expires_at > now()",
                        (rs, row) -> new Snapshot(
                                id,
                                rs.getString("owner"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("expires_at").toInstant(),
                                mapper.readValue(rs.getString("sections"), new TypeReference<List<Section>>() {})),
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, DEF.tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, TABLE, EXPIRED, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, EXPIRED, cutoff, limit);
    }
}
