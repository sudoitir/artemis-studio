package io.github.sudoitir.artemisstudio.kernel.core;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Creates the time partitions of Studio's high-volume history ahead of the writes that need them
 * (ADR-0193). It lives here, and not with the maintainer that implements it, so that a writer in a
 * module the lifecycle depends on can retry through it.
 *
 * <p>The history has no default partition, so a write whose time has no partition fails with
 * SQLState {@code 23514}. The write is retried once after {@link #ensureAhead()}. The retry has to
 * start a new transaction, because the failed statement aborted the first: pass a
 * {@code write} that opens its own.
 */
public interface PartitionProvisioner {

    private static boolean noPartition(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && "23514".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains("no partition of relation")) {
                return true;
            }
        }
        return false;
    }

    /** Creates every missing partition for the current and coming periods. Idempotent. */
    void ensureAhead();

    /** Runs {@code write}; when it finds no partition, creates the partitions and runs it once more. */
    default <T> T retryingNoPartition(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (!noPartition(e)) {
                throw e;
            }
            ensureAhead();
            return write.get();
        }
    }
}
