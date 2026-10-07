package io.github.sudoitir.artemisstudio.feature.diagnostics;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSessions;
import io.github.sudoitir.artemisstudio.platform.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.platform.broker.CoreAccountCheck;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionCheck;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Diagnostics starts with its direct dependencies only (ADR-0069). The clusters module, which it reads for the
 * cluster list, reaches brokers through the broker module; that is not bootstrapped, so its beans are mocked.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class DiagnosticsModuleTest extends ModuleIntegrationTest {

    @MockitoBean
    BrokerConnections brokerConnections;

    @MockitoBean
    BrokerClientFactory clientFactory;

    @MockitoBean
    BrokerSessions sessions;

    @MockitoBean
    CapabilityProbe capabilityProbe;

    @MockitoBean
    CoreAccountCheck coreAccountCheck;

    @MockitoBean
    CoreSubscriptionCheck coreSubscriptionCheck;

    @MockitoBean
    CoreSubscriptionManager coreSubscriptions;

    @MockitoBean
    NodeCallLimiter limiter;
}
