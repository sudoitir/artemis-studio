package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wraps what a gated service method does once the gate lets it through. The gate must be called outside any
 * transaction, so the write opens its own.
 */
final class GatedWrites {

    private GatedWrites() {}

    static <T> Supplier<T> inTx(TransactionTemplate tx, Supplier<T> write) {
        return () -> tx.execute(status -> write.get());
    }

    static Supplier<Void> inTxVoid(TransactionTemplate tx, Runnable write) {
        return () -> {
            tx.executeWithoutResult(status -> write.run());
            return null;
        };
    }
}
