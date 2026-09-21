package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.platform.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaMessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.MessageOperations;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.platform.governance.ClearViewAudit;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.mcp.McpProperties;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeProperties;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Bulk starts with its direct dependencies only (task 5.14, ADR-0093). It pulls in three feature
 * modules at once, each of which needs platform beans bulk itself never touches; those are mocked
 * here rather than declared as bulk's own dependencies (the same pattern {@code RoutingModuleTest}
 * and {@code TriageModuleTest} use for their own dependencies' needs).
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class BulkModuleTest extends ModuleIntegrationTest {

    /** Queue lifecycle, a direct dependency, locates queues through the scrape module. */
    @MockitoBean
    QueueLocator queueLocator;

    /** And drops a destroyed queue's snapshot rows through it. */
    @MockitoBean
    QueueSnapshotUpsert queueSnapshots;

    /** The queue snapshot cache reads a cluster's live nodes through the clusters module. */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    /** The scrape cycle, in a direct dependency, records split-brain state in the clusters module. */
    @MockitoBean
    SplitBrainRegistry splitBrain;

    /** The single-queue commands connect to a node through the broker module. */
    @MockitoBean
    BrokerConnections brokerConnections;

    /** Queue lifecycle runs its per-node fan-out through the clusters module. */
    @MockitoBean
    BrokerCommands brokerCommands;

    /** The resources module's connection control reads Jolokia list pages through the broker module. */
    @MockitoBean
    BrokerListOps brokerListOps;

    /** Messages purges through the single message-operation entry point in the broker module. */
    @MockitoBean
    MessageOperations messageOperations;

    /** {@code MessageService} reads message bodies through the Jolokia transport directly. */
    @MockitoBean
    JolokiaMessageTransport jolokiaMessageTransport;

    /** ...and sends, browses and replays through the Core client transport directly. */
    @MockitoBean
    CoreMessageTransport coreMessageTransport;

    /** ...and tracks its own Core consumers for the DLQ view through this manager directly. */
    @MockitoBean
    CoreSubscriptionManager coreSubscriptionManager;

    /**
     * The kernel's permission checks walk the scope hierarchy. Mocked as the clusters module's
     * implementation rather than as {@code ScopeHierarchy}: bulk boots that module, so mocking the
     * interface would replace the real {@code clusterEnvironmentIndex} bean that {@code ClusterService}
     * injects by type.
     */
    @MockitoBean
    ClusterEnvironmentIndex clusterEnvironmentIndex;

    /** Preview reads the aggregated queue list, cached in the scrape module. */
    @MockitoBean
    QueueSnapshots queueSnapshotReads;

    /** The aggregator's freshness window is a scrape-module setting. */
    @MockitoBean
    ScrapeProperties scrapeProperties;

    /** The resources module's MCP tools read the MCP module's own settings. */
    @MockitoBean
    McpProperties mcpProperties;

    /** Message bodies reach a response only through the content policy. */
    @MockitoBean
    ContentPolicy contentPolicy;

    /** A masked field revealed by a governance override is itself audited. */
    @MockitoBean
    ClearViewAudit clearViewAudit;
}
