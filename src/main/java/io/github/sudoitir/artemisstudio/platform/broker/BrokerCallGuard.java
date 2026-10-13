package io.github.sudoitir.artemisstudio.platform.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Refuses a broker call made while a database transaction is active: the call would hold a database
 * connection for the whole network round trip (and for as long as the per-node rate limiter makes it wait).
 *
 * <p>Checked where a Jolokia request is sent ({@code JolokiaBrokerClient}) and where a Core connection is
 * built ({@code CoreConnectionFactory}). A pooled Core connection that is reused does not pass the second.
 * Switched on by {@code artemis-studio.broker.forbid-calls-in-transaction}: on in the tests, so a violation
 * fails the test that exercises it, and off in production, where a call inside a transaction is slow, not wrong.
 */
@Component
public class BrokerCallGuard {

    private static volatile boolean forbidden;

    BrokerCallGuard(@Value("${artemis-studio.broker.forbid-calls-in-transaction:false}") boolean forbid) {
        forbidden = forbid;
    }

    static boolean enabled() {
        return forbidden;
    }

    static void check() {
        if (forbidden && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("broker call inside a database transaction");
        }
    }
}
