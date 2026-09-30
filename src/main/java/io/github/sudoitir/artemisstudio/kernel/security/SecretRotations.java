package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretRotations implements SmartInitializingSingleton {

    static final int BATCH = 500;

    /**
     * How long after the start (by the database clock) a rotation must have stood before it can succeed: longer than
     * {@link SecretVault#REFRESH_INTERVAL} with room for a seal already in flight, so afterwards every replica has
     * either learned the new current version or refuses to seal.
     */
    static final Duration SETTLE = SecretVault.REFRESH_INTERVAL.multipliedBy(3).plusSeconds(15);

    /** The stored counts and the provider's keys are read again at most this often by the status view. */
    static final Duration STATUS_TTL = Duration.ofSeconds(10);

    private static final Duration KEYRING_TTL = Duration.ofSeconds(30);

    private static final String COLUMNS =
            "id, from_version, to_version, status, started_by, started_at, finished_at, rewrapped, remaining, error";

    private static final String SELECT = "SELECT " + COLUMNS + " FROM secret_rotation";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretVault vault;
    private final List<SealedStore> stores;
    private final Clock clock;

    private volatile Counts counts;
    private volatile Instant keyringLoadedAt = Instant.MIN;
    private volatile Set<Integer> warnedMissing = Set.of();

    private record Counts(Instant at, Map<Integer, Long> byVersion) {}

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

    /**
     * Provider, versions and counts; never a key.
     *
     * @param missingVersions versions that stored secrets are wrapped under but the provider does not hold
     */
    public record Status(
            String provider,
            int currentVersion,
            List<Integer> availableVersions,
            List<Integer> missingVersions,
            Map<Integer, Long> countsByVersion,
            Optional<Rotation> lastRotation) {}

    /** Reports secrets stored under a key version the provider does not hold. */
    @Override
    public void afterSingletonsInstantiated() {
        countsByVersion();
    }

    /**
     * Rotates to the highest version the provider holds now, or, when that is already current, finishes what a failed
     * or interrupted rotation left under an older version.
     *
     * @throws ConflictException when a rotation is running or there is neither a newer version nor a secret under an older one
     */
    public Rotation start(String startedBy) {
        if (running().isPresent()) {
            throw new ConflictException("rotation-running", "A key rotation is already running.");
        }
        int target = vault.reloadKeyring().highest();
        int current = vault.refreshCurrentVersion();
        int from = current;
        if (target <= current) {
            // A failed or interrupted rotation: re-wrap what is still under an older version, keeping the current one.
            from = countsByVersion().keySet().stream()
                    .filter(v -> v < current)
                    .findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "no-newer-key",
                            "The provider holds no key version newer than " + current
                                    + ". Add a newer key version to the provider first."));
            target = current;
        }
        int to = target;
        int fromVersion = from;
        // Honest before the first pass ends: everything under an older version still has to be re-wrapped.
        long remaining = countBelow(to);
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update(
                        "INSERT INTO secret_rotation (id, from_version, to_version, status, started_by, started_at,"
                                + " remaining) VALUES (?, ?, ?, 'RUNNING', ?, now(), ?)",
                        id,
                        fromVersion,
                        to,
                        startedBy,
                        remaining);
                if (to > current) {
                    jdbc.update("UPDATE secret_key_state SET current_kek_version = ?, updated_at = now()", to);
                }
            });
        } catch (DuplicateKeyException _) {
            throw new ConflictException("rotation-running", "A key rotation is already running.");
        }
        vault.refreshCurrentVersion();
        counts = null;
        return byId(id);
    }

    public Optional<Rotation> running() {
        return jdbc.query(SELECT + " WHERE status = 'RUNNING'", SecretRotations::map).stream()
                .findFirst();
    }

    public Optional<Rotation> last() {
        return jdbc.query(SELECT + " ORDER BY started_at DESC LIMIT 1", SecretRotations::map).stream()
                .findFirst();
    }

    /**
     * The keyring is asked again at most every {@link #KEYRING_TTL} and only while no rotation runs; the counts are
     * cached for {@link #STATUS_TTL}, so polling does not scan the sealed tables or hit the provider each time.
     */
    public Status status() {
        Keyring keyring = vault.keyring();
        Instant now = clock.instant();
        if (now.isAfter(keyringLoadedAt.plus(KEYRING_TTL)) && running().isEmpty()) {
            keyringLoadedAt = now;
            try {
                keyring = vault.reloadKeyring();
            } catch (RuntimeException _) {
                // the provider is unreachable now; the keys loaded at start are still shown
            }
        }
        Counts cached = counts;
        Map<Integer, Long> byVersion =
                cached != null && now.isBefore(cached.at().plus(STATUS_TTL)) ? cached.byVersion() : countsByVersion();
        Keyring shown = keyring;
        return new Status(
                vault.providerName(),
                vault.refreshCurrentVersion(),
                List.copyOf(shown.keys().keySet()),
                byVersion.keySet().stream().filter(v -> shown.get(v).isEmpty()).toList(),
                byVersion,
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
            for (SealedStore store : stores) {
                Object after = null;
                do {
                    SealedStore.Batch batch =
                            store.rewrapBatch(after, target, BATCH, blob -> vault.rewrap(blob, target));
                    after = batch.last();
                    rewrapped += batch.updated();
                    jdbc.update("UPDATE secret_rotation SET rewrapped = ? WHERE id = ?", rewrapped, rotation.id());
                } while (after != null);
            }
        } catch (RewrapException e) {
            jdbc.update(
                    "UPDATE secret_rotation SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?",
                    e.getMessage(),
                    rotation.id());
            return;
        }
        long remaining = countBelow(target);
        progress(rotation.id(), rewrapped, remaining);
        counts = null;
        if (remaining == 0) {
            // The settle time is measured by the database clock that stamped started_at, not this replica's.
            jdbc.update(
                    "UPDATE secret_rotation SET status = 'SUCCEEDED', finished_at = now() WHERE id = ?"
                            + " AND now() - started_at > make_interval(secs => ?)",
                    rotation.id(),
                    (double) SETTLE.toSeconds());
        }
    }

    /** Scans every store, caches the result and warns about versions the provider does not hold. */
    private Map<Integer, Long> countsByVersion() {
        Map<Integer, Long> byVersion = new TreeMap<>();
        for (SealedStore store : stores) {
            store.countByVersion().forEach((version, n) -> byVersion.merge(version, n, Long::sum));
        }
        counts = new Counts(clock.instant(), byVersion);
        Keyring keyring = vault.keyring();
        Set<Integer> missing = Set.copyOf(byVersion.keySet().stream()
                .filter(v -> keyring.get(v).isEmpty())
                .toList());
        if (!missing.equals(warnedMissing)) {
            warnedMissing = missing;
            missing.stream()
                    .sorted()
                    .forEach(v -> log.warn(
                            "{} stored secrets are wrapped under key version {}, which secret provider '{}' does not"
                                    + " hold. They cannot be opened until that version is restored.",
                            byVersion.get(v),
                            v,
                            vault.providerName()));
        }
        return byVersion;
    }

    private long countBelow(int version) {
        return stores.stream().mapToLong(s -> s.countBelow(version)).sum();
    }

    private void progress(UUID id, long rewrapped, long remaining) {
        jdbc.update("UPDATE secret_rotation SET rewrapped = ?, remaining = ? WHERE id = ?", rewrapped, remaining, id);
    }

    private Rotation byId(UUID id) {
        return jdbc.queryForObject(SELECT + " WHERE id = ?", SecretRotations::map, id);
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
