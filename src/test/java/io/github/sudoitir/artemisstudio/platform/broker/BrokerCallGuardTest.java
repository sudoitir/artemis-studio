package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BrokerCallGuardTest {

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void refusesABrokerCallWhileATransactionIsActive() {
        BrokerCallGuard guard = new BrokerCallGuard(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker call inside a database transaction");
    }

    @Test
    void allowsTheCallOutsideATransaction() {
        BrokerCallGuard guard = new BrokerCallGuard(true);

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void doesNothingWhenSwitchedOff() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(BrokerCallGuard.OFF::check).doesNotThrowAnyException();
    }
}
