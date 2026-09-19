package io.github.sudoitir.artemisstudio.feature.metrics;

import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Metrics starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class MetricsModuleTest extends ModuleIntegrationTest {

    /** The scrape cycle, in a direct dependency, records split-brain state in the clusters module. */
    @MockitoBean
    SplitBrainRegistry splitBrain;

    /** The scrape module's queue locator, in a direct dependency, lists a cluster's nodes... */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    /** ...and searches them for a queue the scrape has not reached. */
    @MockitoBean
    BrokerConnections brokerConnections;

    /** The kernel's permission checks walk the scope hierarchy the clusters module implements. */
    @MockitoBean
    ScopeHierarchy scopeHierarchy;
}
