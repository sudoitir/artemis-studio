package io.github.sudoitir.artemisstudio.kernel.core;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounds for endpoints whose cost grows with the data they cover (ADR-0196): exports, previews, history
 * and graph reads, and the SQL console. Each request holds a permit for as long as it runs, runs against
 * a time bound and a row cap, and says which of the two it reached.
 *
 * <p>Permits are rows of the UNLOGGED {@code heavy_permit} table, so the limits hold whichever replica
 * serves a request; a per-replica count would multiply every limit by the number of replicas. A user has
 * at most {@link Endpoint#perUser()} permits per endpoint, and the installation at most a quarter of the
 * connection pool, so heavy work cannot take every connection from the ordinary requests. A permit lapses
 * with its request's time bound plus {@link #GRACE}, so the permits of a replica that died do not hold a
 * limit for ever.
 */
@Component
public class HeavyRequests {

    /** How long past its time bound a permit stays, so a request that ends on its bound still holds it. */
    static final Duration GRACE = Duration.ofSeconds(30);

    private static final String INSTALLATION = "*";

    private static final String CLAIM = """
            WITH free AS (
                SELECT s FROM generate_series(1, ?) s
                WHERE NOT EXISTS (
                    SELECT 1 FROM heavy_permit p
                    WHERE p.owner = ? AND p.endpoint = ? AND p.slot = s AND p.expires_at > now())
                ORDER BY s LIMIT 1)
            INSERT INTO heavy_permit AS p (expires_at, slot, owner, endpoint, replica_id)
            SELECT now() + make_interval(secs => ?), s, ?, ?, ? FROM free
            ON CONFLICT (owner, endpoint, slot)
            DO UPDATE SET expires_at = EXCLUDED.expires_at, replica_id = EXCLUDED.replica_id
            WHERE p.expires_at <= now()
            RETURNING slot""";

    private static final String RELEASE =
            "DELETE FROM heavy_permit WHERE owner = ? AND endpoint = ? AND slot = ? AND replica_id = ?";

    /**
     * One kind of heavy request.
     *
     * @param name what a refusal calls it, such as {@code audit exports}
     * @param perUser how many one user may have running, across all replicas
     * @param time how long a request may run
     * @param rows how many rows a request may return
     */
    public record Endpoint(String name, int perUser, Duration time, int rows) {}

    /** The bound a request stopped at. */
    public enum Bound {
        ROWS,
        TIME
    }

    private final JdbcTemplate jdbc;
    private final int installationCap;
    private final UUID replica = UUID.randomUUID();

    public HeavyRequests(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.installationCap = Math.max(1, poolSize(dataSource) / 4);
    }

    private static int poolSize(DataSource dataSource) {
        try {
            return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        } catch (SQLException _) {
            return 10;
        }
    }

    /** The most heavy requests the installation runs at once. */
    public int installationCap() {
        return installationCap;
    }

    /**
     * Runs {@code work} holding a permit of {@code endpoint} for {@code owner}.
     *
     * @throws HeavyLimitException when the user's or the installation's limit is full
     */
    public <T> T run(Endpoint endpoint, String owner, Function<Run, T> work) {
        try (Run run = open(endpoint, owner)) {
            return work.apply(run);
        }
    }

    /** Like {@link #run}, for a caller that releases the permit itself, such as a stream that outlives the call. */
    public Run open(Endpoint endpoint, String owner) {
        Duration lease = endpoint.time().plus(GRACE);
        Integer slot = claim(owner, endpoint.name(), endpoint.perUser(), lease);
        if (slot == null) {
            throw HeavyLimitException.perUser(endpoint.name(), endpoint.perUser());
        }
        Integer shared = claim(INSTALLATION, INSTALLATION, installationCap, lease);
        if (shared == null) {
            release(owner, endpoint.name(), slot);
            throw HeavyLimitException.installation(endpoint.name(), installationCap);
        }
        return new Run(endpoint, owner, slot, shared);
    }

    private Integer claim(String owner, String endpoint, int cap, Duration lease) {
        double seconds = lease.toMillis() / 1000.0;
        // A lost race for the same free slot returns nothing although a slot was free; the next try sees
        // the winner's row and picks another. A full table returns nothing every time, after cap + 1 tries.
        for (int attempt = 0; attempt <= cap; attempt++) {
            List<Integer> slot =
                    jdbc.queryForList(CLAIM, Integer.class, cap, owner, endpoint, seconds, owner, endpoint, replica);
            if (!slot.isEmpty()) {
                return slot.getFirst();
            }
        }
        return null;
    }

    private void release(String owner, String endpoint, int slot) {
        jdbc.update(RELEASE, owner, endpoint, slot, replica);
    }

    /** A running heavy request: its permit, its deadline and its row count. Closing releases the permit. */
    public final class Run implements AutoCloseable {

        private final Endpoint endpoint;
        private final String owner;
        private final int slot;
        private final int installationSlot;
        private final long deadlineNanos;
        private int rows;
        private Bound reached;

        private Run(Endpoint endpoint, String owner, int slot, int installationSlot) {
            this.endpoint = endpoint;
            this.owner = owner;
            this.slot = slot;
            this.installationSlot = installationSlot;
            this.deadlineNanos = System.nanoTime() + endpoint.time().toNanos();
        }

        /**
         * Asks to take one more row. False once the row cap is full or the time bound is past, and then
         * {@link #reached()} says which; a request that ends before either never asks for a row it
         * does not have, so it is complete.
         */
        public boolean admit() {
            if (reached != null) {
                return false;
            }
            if (rows >= endpoint.rows()) {
                reached = Bound.ROWS;
                return false;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                reached = Bound.TIME;
                return false;
            }
            rows++;
            return true;
        }

        /** The statement that gives the current transaction what is left of the time bound. */
        public String statementTimeout() {
            return StatementTimeout.setLocal(timeLeft());
        }

        /** What is left of the time bound; the database may cut a statement at this. */
        public Duration timeLeft() {
            return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
        }

        /** Notes that the database cut a statement at the time bound. */
        public void timedOut() {
            reached = Bound.TIME;
        }

        /** The bound the request stopped at, or null when it ran to its end. */
        public Bound reached() {
            return reached;
        }

        /** Whether the rows are fewer than the data held. */
        public boolean truncated() {
            return reached != null;
        }

        /** The most rows the request may return. */
        public int rowCap() {
            return endpoint.rows();
        }

        @Override
        public void close() {
            try {
                release(INSTALLATION, INSTALLATION, installationSlot);
            } finally {
                release(owner, endpoint.name(), slot);
            }
        }
    }
}
