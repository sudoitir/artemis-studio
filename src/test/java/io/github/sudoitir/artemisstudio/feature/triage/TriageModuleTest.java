package io.github.sudoitir.artemisstudio.feature.triage;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Triage starts with its direct dependencies only — the check behind ADR-0089's claim
 * that the consumer-health verdict needed no new module and opened no cycle.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class TriageModuleTest extends ModuleIntegrationTest {

    /** The scrape cycle, in a direct dependency, records split-brain state in the clusters module. */
    @MockitoBean
    SplitBrainRegistry splitBrain;

    /** The scrape module's queue locator lists a cluster's nodes... */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    /** ...and searches them for a queue the scrape has not reached. */
    @MockitoBean
    BrokerConnections brokerConnections;

    /**
     * The kernel's permission checks walk the scope hierarchy. Mocked as the clusters
     * module's implementation rather than as {@code ScopeHierarchy}: triage boots that
     * module, so mocking the interface would replace the real {@code clusterEnvironmentIndex}
     * bean that {@code ClusterService} injects by type.
     */
    @MockitoBean
    io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex clusterEnvironmentIndex;

    /** Broker events, a direct dependency, mask notification props through the governance policy. */
    @MockitoBean
    io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy contentPolicy;
}
