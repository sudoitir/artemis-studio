package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSessions;
import io.github.sudoitir.artemisstudio.platform.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionCheck;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Queues starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class QueuesModuleTest extends ModuleIntegrationTest {

    // The clusters module, a direct dependency, registers clusters and runs broker commands over
    // the broker module, which queues itself does not use.

    @MockitoBean
    BrokerClientFactory clientFactory;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerSessions sessions;

    @MockitoBean
    CapabilityProbe capabilityProbe;

    @MockitoBean
    CoreSubscriptionCheck coreSubscriptionCheck;

    @MockitoBean
    CoreSubscriptionManager coreSubscriptions;

    @MockitoBean
    NodeCallLimiter limiter;
}
