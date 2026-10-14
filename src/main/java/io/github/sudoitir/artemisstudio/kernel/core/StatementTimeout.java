package io.github.sudoitir.artemisstudio.kernel.core;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every Studio connection starts with a 30 s statement timeout (ADR-0195). Work that is legitimately
 * longer, such as an export, a lifecycle preview or partition maintenance, raises it for its own
 * transaction with {@code SET LOCAL}, so no other connection and no later transaction inherits it.
 * {@link #LONG} stays below the driver's socket timeout, which would otherwise cut the read first.
 */
public final class StatementTimeout {

    /** The ceiling for work that is allowed past the ordinary timeout. */
    public static final Duration LONG = Duration.ofMinutes(10);

    private StatementTimeout() {}

    /** The statement that sets {@code timeout} for the current transaction. */
    public static String setLocal(Duration timeout) {
        return "SET LOCAL statement_timeout = " + Math.max(1000, timeout.toMillis());
    }

    /** Runs {@code work} on {@code jdbc} in one transaction with the {@link #LONG} timeout. */
    public static <T> T inLongTransaction(JdbcTemplate jdbc, Supplier<T> work) {
        var transactions =
                new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        return transactions.execute(status -> {
            jdbc.execute(setLocal(LONG));
            return work.get();
        });
    }
}
