package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.platform.governance.ClearViewAudit;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.mcp.McpProperties;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Transfer starts with its direct dependencies only (ADR-0069, ADR-0097). The messages module it
 * depends on for its permissions needs platform beans transfer itself never touches; those are mocked
 * here rather than declared as transfer's own dependencies.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class TransferModuleTest extends ModuleIntegrationTest {

    /** Message bodies reach a response only through the content policy. */
    @MockitoBean
    ContentPolicy contentPolicy;

    /** A masked field revealed by a governance override is itself audited. */
    @MockitoBean
    ClearViewAudit clearViewAudit;

    /** The messages module's MCP tools read the MCP module's own settings. */
    @MockitoBean
    McpProperties mcpProperties;

    /**
     * The kernel's permission checks walk the scope hierarchy, implemented by the clusters module, which
     * transfer boots; mocked as that implementation, as {@code BulkModuleTest} explains.
     */
    @MockitoBean
    ClusterEnvironmentIndex clusterEnvironmentIndex;
}
