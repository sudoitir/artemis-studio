package io.github.sudoitir.artemisstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * {@code /mcp} is not part of {@code /api/**}, so {@link
 * io.github.sudoitir.artemisstudio.web.EndpointProtectionTest} — which reflects
 * over {@code RequestMappingHandlerMapping} — cannot see it at all: the MCP
 * transport registers a functional {@code RouterFunction}, not an
 * {@code @RequestMapping}. This test covers the gap that leaves.
 *
 * <p>It also pins the two routing facts the design could not assume (design D9):
 * that {@code POST /mcp} reaches the transport rather than the SPA fallback, and
 * that {@code GET /mcp} 404s rather than returning {@code index.html} with a 200.
 */
class McpEndpointProtectionTest extends PostgresIntegrationTest {

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

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * A valid CSRF token is attached for the same reason as in {@code
     * EndpointProtectionTest}: without it {@code CsrfFilter} answers {@code 403}
     * first and would mask what this test is actually asserting — that {@code /mcp}
     * requires authentication. A real MCP client never hits that path anyway, since
     * an {@code Authorization} header makes the request CSRF-exempt by design.
     */
    @Test
    void mcpRejectsAnUnauthenticatedCall() throws Exception {
        var response = mvc().perform(MockMvcRequestBuilders.post("/mcp")
                        .with(csrf())
                        .contentType("application/json")
                        .accept("application/json", "text/event-stream")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus())
                .describedAs("body was: %s", response.getContentAsString())
                .isEqualTo(401);
    }

    @Test
    void mcpAnswersToolsListForAValidApiKey() throws Exception {
        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));

        JsonNode envelope = McpFixture.rpc(mvc(), key, "tools/list", null);

        assertThat(envelope.has("error"))
                .describedAs("tools/list returned a JSON-RPC error: %s", envelope)
                .isFalse();
        assertThat(envelope.path("result").path("tools").isArray()).isTrue();
        assertThat(envelope.path("result").path("tools")).isNotEmpty();
    }

    /**
     * A bare {@code GET /mcp} is what a human or a misconfigured client actually
     * sends. Without the {@code mcp} exclusion in {@code SpaRoutingConfig} it would
     * come back as the SPA shell with a {@code 200} — a routing miss disguised as
     * success. 404 is the honest answer.
     */
    @Test
    void aGetProbeDoesNotFallThroughToTheSpaShell() throws Exception {
        var response =
                mvc().perform(MockMvcRequestBuilders.get("/mcp")).andReturn().getResponse();
        assertThat(response.getStatus()).isNotEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain("<!doctype html");
    }
}
