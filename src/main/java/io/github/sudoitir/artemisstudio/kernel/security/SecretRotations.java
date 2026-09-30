package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The state of key-encryption-key rotations and the sweep that finishes one (ADR-0132 D5).
 *
 * <p>{@link #start} stores the new current version and a {@code RUNNING} row in one transaction, so new secrets use
 * the new key at once. {@link #sweep} re-wraps every {@link SealedStore} until none holds a blob under an older
 * version; that condition, not a list of rows, ends the rotation, so a replica that wrote under a stale version
 * and a restart are both covered. Who may start one, and its audit, are the caller's.
 */
@Component
@RequiredArgsConstructor
public class SecretRotations {

    static final int BATCH = 500;

    private static final String COLUMNS =
            "id, from_version, to_version, status, started_by, started_at, finished_at, rewrapped, remaining, error";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretVault vault;
    private final List<SealedStore> stores;
    private final Clock clock;

    public record Rotation(
            UUID id,
            int fromVersion,
            int toVersion,
            String status,
            String startedBy,
            Instant startedAt,
            Instant finishedAt,
            long rewrapped,
            long remaining,
            String error) {}

    /** Provider, versions and counts; never a key. */
    public record Status(
            String provider,
            int currentVersion,
            List<Integer> availableVersions,
            Map<Integer, Long> countsByVersion,
            Optional<Rotation> lastRotation) {}

    /**
     * Rotates to the highest version the provider holds now.
     *
     * @throws ConflictException when a rotation is running or the provider has no version newer than the current one
     */
    public Rotation start(String startedBy) {
        if (running().isPresent()) {
            throw new ConflictException("rotation-running", "A key rotation is already running.");
        }
        int target = vault.reloadKeyring().highest();
        int current = vault.refreshCurrentVersion();
        if (target <= current) {
            throw new ConflictException(
                    "no-newer-key",
                    "The provider holds no key version newer than " + current
                            + ". Add a newer key version to the provider first.");
        }
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update(
                        "INSERT INTO secret_rotation (id, from_version, to_version, status, started_by, started_at)"
                                + " VALUES (?, ?, ?, 'RUNNING', ?, ?)",
                        id,
                        current,
                        target,
                        startedBy,
                        java.sql.Timestamp.from(clock.instant()));
                jdbc.update("UPDATE secret_key_state SET current_kek_version = ?, updated_at = now()", target);
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("rotation-running", "A key rotation is already running.");
        }
        vault.refreshCurrentVersion();
        return byId(id);
    }

    public Optional<Rotation> running() {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM secret_rotation WHERE status = 'RUNNING'", SecretRotations::map)
                .stream()
                .findFirst();
    }

    public Optional<Rotation> last() {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM secret_rotation ORDER BY started_at DESC LIMIT 1",
                        SecretRotations::map)
                .stream()
                .findFirst();
    }

    /** The keyring is asked again only while no rotation runs, so polling a running one does not hit the provider. */
    public Status status() {
        Keyring keyring = vault.keyring();
        if (running().isEmpty()) {
            try {
                keyring = vault.reloadKeyring();
            } catch (RuntimeException e) {
                // the provider is unreachable now; the keys loaded at start are still shown
            }
        }
        Map<Integer, Long> counts = new TreeMap<>();
        for (SealedStore store : stores) {
            store.countByVersion().forEach((version, n) -> counts.merge(version, n, Long::sum));
        }
        return new Status(
                vault.providerName(),
                vault.refreshCurrentVersion(),
                List.copyOf(keyring.keys().keySet()),
                counts,
                last());
    }

    /** One pass of the sweep: a no-op unless a rotation is running. */
    public void sweep() {
        Optional<Rotation> running = running();
        if (running.isEmpty()) {
            return;
        }
        vault.refreshCurrentVersion();
        Rotation rotation = running.get();
        int target = rotation.toVersion();
        long rewrapped = rotation.rewrapped();
        try {
            progress(rotation.id(), rewrapped, countBelow(target));
            for (SealedStore store : stores) {
                int n;
                while ((n = store.rewrapBatch(target, target, BATCH, blob -> vault.rewrap(blob, target))) > 0) {
                    rewrapped += n;
                    jdbc.update("UPDATE secret_rotation SET rewrapped = ? WHERE id = ?", rewrapped, rotation.id());
                }
            }
        } catch (SealedStore.RewrapException e) {
            jdbc.update(
                    "UPDATE secret_rotation SET status = 'FAILED', finished_at = ?, error = ? WHERE id = ?",
                    java.sql.Timestamp.from(clock.instant()),
                    e.getMessage(),
                    rotation.id());
            return;
        }
        long remaining = countBelow(target);
        progress(rotation.id(), rewrapped, remaining);
        if (remaining == 0) {
            jdbc.update(
                    "UPDATE secret_rotation SET status = 'SUCCEEDED', finished_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(clock.instant()),
                    rotation.id());
        }
    }

    private long countBelow(int version) {
        return stores.stream().mapToLong(s -> s.countBelow(version)).sum();
    }

    private void progress(UUID id, long rewrapped, long remaining) {
        jdbc.update("UPDATE secret_rotation SET rewrapped = ?, remaining = ? WHERE id = ?", rewrapped, remaining, id);
    }

    private Rotation byId(UUID id) {
        return jdbc.queryForObject(
                "SELECT " + COLUMNS + " FROM secret_rotation WHERE id = ?", SecretRotations::map, id);
    }

    private static Rotation map(ResultSet rs, int row) throws SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new Rotation(
                rs.getObject("id", UUID.class),
                rs.getInt("from_version"),
                rs.getInt("to_version"),
                rs.getString("status"),
                rs.getString("started_by"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getLong("rewrapped"),
                rs.getLong("remaining"),
                rs.getString("error"));
    }
}
