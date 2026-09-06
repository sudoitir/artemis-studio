package io.github.sudoitir.artemisstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The catalogue must be identical for every caller and every call.
 *
 * <p>Hosts cache the tool listing in the prompt prefix. A listing that varied by
 * what the key can reach — hiding {@code queue_action} from a read-only key, say —
 * would look like helpful tailoring and would invalidate that cache on every
 * connection, re-billing the whole prefix. It would also mislead: a tool absent
 * from the listing reads as "this product cannot do that", when the truth is "this
 * key may not". Capability gating belongs in {@code studio://permissions} and in
 * the refusal, not in the catalogue.
 */
class McpToolCatalogueTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

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

    private UUID cluster;

    @AfterEach
    void cleanUp() {
        if (cluster != null) {
            clusters.deleteById(cluster);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    private List<String> namesFor(Grant.ScopeType scopeType, UUID scopeId, Set<String> permissions) throws Exception {
        var key = McpFixture.mintKey(users, roles, rolePermissions, userRoles, tokens, scopeType, scopeId, permissions);
        JsonNode tools =
                McpFixture.rpc(mvc(), key, "tools/list", null).path("result").path("tools");
        return tools.valueStream().map(t -> t.path("name").asString()).toList();
    }

    @Test
    void theCatalogueIsIdenticalWhateverTheKeyHolds() throws Exception {
        cluster = clusters.save(new ClusterEntity("catalogue-" + UUID.randomUUID(), null, null))
                .getId();

        List<String> readOnly = namesFor(Grant.ScopeType.CLUSTER, cluster, Set.of(Permissions.CLUSTER_READ));
        List<String> privileged = namesFor(
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(
                        Permissions.CLUSTER_READ,
                        Permissions.QUEUE_PURGE,
                        Permissions.MESSAGE_SEND,
                        Permissions.SETTINGS_WRITE));

        assertThat(readOnly)
                .describedAs("the tool catalogue must not vary by what the key can reach")
                .isEqualTo(privileged);
        assertThat(readOnly).isNotEmpty();
    }

    @Test
    void theOrderingIsStableAcrossCalls() throws Exception {
        List<String> first = namesFor(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.CLUSTER_READ));
        List<String> second = namesFor(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.CLUSTER_READ));
        assertThat(first)
                .describedAs("a reordered listing invalidates every host's cached prompt prefix")
                .isEqualTo(second);
    }
}
