package io.github.sudoitir.artemisstudio.persist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A per-cluster Postgres advisory lock, so that two Studio instances sharing one
 * database do not both reconcile the same cluster (ADR-0062 D5).
 *
 * <p>Ownership encoded in a divert's name defends against a <em>different</em> Studio;
 * it cannot defend against a second instance of the <em>same</em> one, which shares the
 * instance id and would see its peer's taps as its own. This is that defence.
 *
 * <p>Not holding the lock is not a failure. The other instance is doing the work, so
 * the correct response is to stop reconciling until the next pass — not to log an
 * error, not to raise an alert, and above all not to act anyway. The same is true of a
 * database that is momentarily unavailable: the pass is skipped, and the reconciler is
 * an idempotent loop that converges on the next one.
 *
 * <p>The lock is session-scoped and therefore held on one pooled connection for the
 * duration of the pass, which includes broker calls. Passes are sequential, so at most
 * one connection is held this way at a time; Postgres releases the lock on its own if
 * the connection dies, which is what makes a crashed instance recoverable without a
 * lease table or a heartbeat.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterLock {

    /**
     * The first half of the advisory-lock key, distinguishing Studio's locks from any
     * other application's on the same database. {@code 0x4153} is "AS".
     */
    private static final int NAMESPACE = 0x4153;

    private final DataSource dataSource;

    /**
     * Run {@code work} while holding this cluster's lock, or do nothing at all.
     *
     * @return whether the work ran. {@code false} means another instance holds the
     *     lock, or the database could not be reached — both of which mean "stop
     *     reconciling", not "something is wrong".
     */
    public boolean runIfHeld(UUID clusterId, Runnable work) {
        int key = key(clusterId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!call(connection, "SELECT pg_try_advisory_lock(?, ?)", key)) {
                log.debug("Cluster {} is being reconciled by another instance; skipping this pass", clusterId);
                return false;
            }
            try {
                work.run();
            } finally {
                call(connection, "SELECT pg_advisory_unlock(?, ?)", key);
            }
            return true;
        } catch (SQLException e) {
            log.debug("Could not take the reconcile lock for cluster {}: {}", clusterId, e.getMessage());
            return false;
        }
    }

    private boolean call(Connection connection, String sql, int key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * The second half of the key. A UUID does not fit in the 32 bits Postgres gives
     * this half, so it is folded; a collision would mean two clusters serialise against
     * each other for the length of a pass, which is a cost and not a correctness
     * problem.
     */
    private static int key(UUID clusterId) {
        return Long.hashCode(clusterId.getMostSignificantBits() ^ clusterId.getLeastSignificantBits());
    }
}
