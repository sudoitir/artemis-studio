package io.github.sudoitir.artemisstudio.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.internal.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The MCP surface is an optional module (ADR-0069): disabled, there is no server and no
 * {@code /mcp} endpoint, not an endpoint that refuses.
 */
@SpringBootTest(properties = "artemis-studio.features.mcp.enabled=false")
class McpDisabledTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    @Autowired
    McpToolCatalog catalog;

    @Test
    void aDisabledMcpModuleHasNoServerAndNoEndpoint() throws Exception {
        assertThat(webContext.getBeansOfType(McpStatelessSyncServer.class)).isEmpty();

        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));
        var response = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build()
                .perform(MockMvcRequestBuilders.post("/mcp")
                        .header("Authorization", key.bearer())
                        .contentType("application/json")
                        .accept("application/json", "text/event-stream")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .describedAs("body was: %s", response.getContentAsString())
                .isEqualTo(404);
    }

    @Test
    void theCatalogueOmitsTheDisabledModulesTools() {
        assertThat(catalog.toolNames()).doesNotContain("diagnose", "activity_log", McpToolCatalog.HELP_TOOL);
    }
}
