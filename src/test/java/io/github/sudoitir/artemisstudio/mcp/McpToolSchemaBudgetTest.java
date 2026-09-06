package io.github.sudoitir.artemisstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The budget that stops this surface rotting back into a REST mirror.
 *
 * <p>{@code tools/list} is sent to the model on every conversation that touches
 * Studio, before it has asked for anything. Its size is a permanent tax, and the
 * pressure on it is one-directional: every future change adds a tool or a
 * parameter, and none removes one. Review does not catch that, because each
 * individual addition looks reasonable. A build failure does.
 *
 * <p>The token estimate is {@code chars / 4} — crude, model-independent, and
 * consistently wrong in the same direction, which is all a budget needs. If a
 * change makes this fail, the fix is a shorter description or a collapsed tool
 * family, not a raised ceiling.
 */
class McpToolSchemaBudgetTest extends PostgresIntegrationTest {

    /** Roughly a fifth of a small model's context spent before the first question. */
    private static final int TOTAL_TOKEN_BUDGET = 2000;

    /** A tool that needs more than this is describing too much; split it or collapse it. */
    private static final int PER_TOOL_TOKEN_BUDGET = 200;

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

    private JsonNode toolList() throws Exception {
        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        return McpFixture.rpc(mvc, key, "tools/list", null).path("result").path("tools");
    }

    private static int tokens(JsonNode node) {
        return node.toString().length() / 4;
    }

    @Test
    void theWholeToolListingFitsTheBudget() throws Exception {
        JsonNode tools = toolList();
        int total = tokens(tools);
        assertThat(total)
                .describedAs(
                        "tools/list is %d tokens over %d tools. Shorten a description or collapse a "
                                + "family behind a discriminator — do not raise this ceiling.",
                        total, tools.size())
                .isLessThanOrEqualTo(TOTAL_TOKEN_BUDGET);
    }

    @Test
    void noSingleToolDominatesTheListing() throws Exception {
        List<String> over = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            int size = tokens(tool);
            if (size > PER_TOOL_TOKEN_BUDGET) {
                over.add(tool.path("name").asString() + " = " + size + " tokens");
            }
        }
        assertThat(over)
                .describedAs("these tools each cost more than %d tokens to describe", PER_TOOL_TOKEN_BUDGET)
                .isEmpty();
    }

    /**
     * A long enum spelled out in a schema is paid for on every listing. The
     * convention here is to validate discriminators server-side and name the valid
     * values in the rejection message instead (see {@code McpArgs}).
     */
    @Test
    void everyToolDeclaresADescriptionAndAnAnnotationSet() throws Exception {
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            String name = tool.path("name").asString();
            if (tool.path("description").asString("").isBlank()) {
                problems.add(name + " has no description");
            }
            if (!tool.path("annotations").isObject()) {
                problems.add(name + " declares no annotations, so a host cannot tell whether it mutates");
            }
        }
        assertThat(problems).isEmpty();
    }
}
