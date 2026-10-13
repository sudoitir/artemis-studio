package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BrokerCallGuardTest {

    private final boolean before = BrokerCallGuard.enabled();

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        new BrokerCallGuard(before);
    }

    @Test
    void refusesABrokerCallWhileATransactionIsActive() {
        new BrokerCallGuard(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(BrokerCallGuard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker call inside a database transaction");
    }

    @Test
    void allowsTheCallOutsideATransaction() {
        new BrokerCallGuard(true);

        assertThatCode(BrokerCallGuard::check).doesNotThrowAnyException();
    }

    @Test
    void doesNothingWhenSwitchedOff() {
        new BrokerCallGuard(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(BrokerCallGuard::check).doesNotThrowAnyException();
    }
}
