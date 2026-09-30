package io.github.sudoitir.artemisstudio.platform.broker.web;

import static io.github.sudoitir.artemisstudio.support.SignedInSession.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallHealth;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/system/health} against the real security chain: settings-read only, a node
 * that has never been measured reads as unavailable and never as zero, and a failure message is
 * redacted.
 */
class StudioHealthControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NodeCallHealth calls;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private static UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        StudioPrincipal principal = new StudioPrincipal(
                null, "reader", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Test
    void aCallerWithoutSettingsReadIsRefused() throws Exception {
        mvc.perform(get("/api/v1/system/health").with(authentication(callerWith("cluster:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnmeasuredNodeIsUnavailableNotZeroAndAFailureIsRedacted() throws Exception {
        String url = "http://health-" + UUID.randomUUID() + ":8161/console/jolokia";
        clusterId = clusters.save(new ClusterEntity("health-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "quiet", "PRIMARY", UUID.randomUUID().toString());
        node.attachManagementUrl(url);
        nodes.save(node);
        calls.failed(url, "login failed, password=hunter2");

        mvc.perform(get("/api/v1/system/health").with(authentication(callerWith(SettingsPermissions.SETTINGS_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.nodes[?(@.name=='quiet')].managementP95Millis")
                        .value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.nodes[?(@.name=='quiet')].lastError")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2")))))
                .andExpect(jsonPath("$.dbPool.max").isNumber());
    }
}
