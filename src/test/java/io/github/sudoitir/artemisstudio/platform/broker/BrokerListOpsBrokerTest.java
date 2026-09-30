package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every list a view or a sampler reads unfiltered, against a real broker. CI runs it at both
 * ends of the supported range, where an empty options string fails on the oldest (ADR-0142).
 */
class BrokerListOpsBrokerTest extends ArtemisIntegrationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "listQueues",
                "listAddresses",
                "listConnections",
                "listSessions",
                "listConsumers",
                "listProducers"
            })
    void listsEveryRowWithTheAllFilter(String op) {
        BrokerListOps.ListPage page = new BrokerListOps().fetch(jolokiaClient(), op, BrokerListOps.ALL, -1, -1);

        assertThat(page.data()).isNotNull();
        assertThat(page.data().isArray()).isTrue();
    }
}
