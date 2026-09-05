package io.github.sudoitir.artemisstudio.broker.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.ssl.SslBundles;

/** {@link CorePool} against a real broker: pooled sessions are reused and stay usable across borrows. */
class CorePoolTest extends ArtemisIntegrationTest {

    private CorePool pool() {
        ArtemisStudioProperties props =
                new ArtemisStudioProperties(null, null, null, null, null, null, null, null, null, null);
        return new CorePool(new CoreConnectionFactory(props, Mockito.mock(SslBundles.class)));
    }

    private CoreConnectionSettings settings() {
        return new CoreConnectionSettings(UUID.randomUUID(), BROKER_USER, BROKER_PASSWORD, null, true);
    }

    @Test
    void aSecondBorrowOnTheSameKeyStillWorks() throws Exception {
        CorePool pool = pool();
        UUID clusterId = UUID.randomUUID();
        String queueName = "core.pool.it." + System.nanoTime();

        try (CorePool.PooledSession first = pool.borrow(clusterId, coreUrl(), settings())) {
            Queue queue = first.session().createQueue(queueName);
            MessageProducer producer = first.session().createProducer(queue);
            producer.send(first.session().createTextMessage("one"));
        }

        try (CorePool.PooledSession second = pool.borrow(clusterId, coreUrl(), settings())) {
            Queue queue = second.session().createQueue(queueName);
            jakarta.jms.QueueBrowser browser = second.session().createBrowser(queue);
            var e = browser.getEnumeration();
            assertThat(e.hasMoreElements()).isTrue();
            Message m = (Message) e.nextElement();
            assertThat(((TextMessage) m).getText()).isEqualTo("one");
        }

        pool.forget(clusterId);

        // forget() must not poison future use — a fresh pool is built for the same key.
        try (CorePool.PooledSession third = pool.borrow(clusterId, coreUrl(), settings())) {
            assertThat(third.session()).isNotNull();
        }
    }
}
