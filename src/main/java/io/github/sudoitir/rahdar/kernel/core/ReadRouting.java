package io.github.sudoitir.rahdar.kernel.core;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where a heavy read runs (database-schema spec): on the read replica when one is configured
 * ({@code rahdar.database.read.*}) and its replication lag is within the bound, otherwise on the
 * primary. Either way the callback runs in a read-only transaction, so it cannot write by mistake.
 *
 * <p>Only heavy history reads and exports use it. Writes, locks and anything that reads what the
 * same request has just written use the primary directly, because a replica may not have it yet.
 *
 * <p>The lag is measured on the replica ({@code now() - pg_last_xact_replay_timestamp()}) and cached
 * for {@link #LAG_CACHE}. A replica that cannot be reached, or whose lag is unknown, counts as too
 * far behind, and a replica that fails to give a connection sends the read to the primary.
 */
public final class ReadRouting implements AutoCloseable {

    static final Duration LAG_CACHE = Duration.ofSeconds(5);

    private static final int FETCH_SIZE = 500;

    private final TransactionTemplate primary;
    private final TransactionTemplate replica;
    private final DataSource replicaSource;
    private final Duration maxLag;
    private final Supplier<Optional<Duration>> lagProbe;

    /** Whether the replica was within the bound when last checked, and when. */
    private record LagCheck(long atNanos, boolean usable) {}

    private final AtomicReference<LagCheck> lagCheck = new AtomicReference<>();

    /**
     * @param primary the primary's data source
     * @param replica the read-only pool, or {@code null} when none is configured
     * @param maxLag the lag past which reads go to the primary
     * @param lagProbe the replica's current lag, empty when it cannot be told; a seam for tests
     */
    public ReadRouting(DataSource primary, DataSource replica, Duration maxLag, Supplier<Optional<Duration>> lagProbe) {
        this.primary = readOnly(primary);
        this.replica = replica == null ? null : readOnly(replica);
        this.replicaSource = replica;
        this.maxLag = maxLag;
        this.lagProbe = lagProbe;
    }

    private static TransactionTemplate readOnly(DataSource dataSource) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setReadOnly(true);
        return template;
    }

    /** Closes the replica's pool, which this owns. */
    @Override
    public void close() throws Exception {
        if (replicaSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /** Whether a read replica is configured. */
    public boolean hasReplica() {
        return replica != null;
    }

    /** Runs {@code query} on the replica when it is within the lag bound, otherwise on the primary. */
    public <T> Routed<T> read(Function<JdbcClient, T> query) {
        if (replica != null && replicaWithinBound()) {
            try {
                return new Routed<>(run(replica, query), true);
            } catch (CannotCreateTransactionException _) {
                lagCheck.set(new LagCheck(System.nanoTime(), false));
            }
        }
        return new Routed<>(run(primary, query), false);
    }

    private static <T> T run(TransactionTemplate template, Function<JdbcClient, T> query) {
        DataSource dataSource = ((DataSourceTransactionManager) template.getTransactionManager()).getDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Heavy reads are long; a cursor keeps the driver from holding the whole result in memory.
        jdbc.setFetchSize(FETCH_SIZE);
        JdbcClient client = JdbcClient.create(jdbc);
        return template.execute(_ -> query.apply(client));
    }

    private boolean replicaWithinBound() {
        LagCheck check = lagCheck.get();
        long now = System.nanoTime();
        if (check == null || now - check.atNanos() >= LAG_CACHE.toNanos()) {
            check = new LagCheck(
                    now,
                    lagProbe.get().filter(lag -> lag.compareTo(maxLag) <= 0).isPresent());
            lagCheck.set(check);
        }
        return check.usable();
    }
}
