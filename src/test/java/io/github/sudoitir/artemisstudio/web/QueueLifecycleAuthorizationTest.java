package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Each lifecycle route requires its own permission, and a caller without it
 * cannot learn that the cluster exists.
 *
 * <p>The second half matters as much as the first: {@code ClusterAccessGuard}
 * answers {@code 404}, not {@code 403}, precisely so that probing for cluster ids
 * tells an unauthorized caller nothing. A lifecycle route that leaked a
 * {@code 403} would be a hole in that, on the newest and most destructive surface
 * the product has.
 */
class QueueLifecycleAuthorizationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        clusterId = clusters.save(new ClusterEntity("auth-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /** A caller holding exactly {@code permissions} on this cluster, and nothing else. */
    private UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        StudioPrincipal principal = new StudioPrincipal(
                null, "scoped", Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static final String CREATE_BODY = """
            {"address":"a","name":"q","routingType":"ANYCAST"}""";

    // ---- each route demands its own permission ---------------------------

    @Test
    void createNeedsTheCreatePermission() throws Exception {
        // The read permission alone is not enough — and the refusal is a 404.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/queues")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    void purgeDoesNotImplyDestroy() throws Exception {
        // Emptying a queue and removing it are different authorities.
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/clusters/" + clusterId + "/queues/q?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.QUEUE_PURGE))))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    void updateDoesNotImplyPause() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/queues/q/pause?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.QUEUE_UPDATE))))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    void createDoesNotImplyDelete() throws Exception {
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/clusters/" + clusterId + "/addresses/a?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.QUEUE_CREATE))))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    // ---- the grant does not leak cluster existence -----------------------

    @Test
    void aCallerWithoutTheGrantCannotTellARealClusterFromAnInventedOne() throws Exception {
        UUID invented = UUID.randomUUID();
        var caller = callerWith(Permissions.CLUSTER_READ);

        int real = mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/queues")
                        .with(csrf())
                        .with(authentication(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        int fake = mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + invented + "/queues")
                        .with(csrf())
                        .with(authentication(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(real).isEqualTo(404);
        assertThat(fake).isEqualTo(real);
    }

    @Test
    void theRightPermissionGetsPastTheGuard() throws Exception {
        // The control: with the grant, the request is no longer a 404 — proving the
        // 404s above come from the guard and not from the route being absent.
        int status = mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/queues?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.QUEUE_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(404);
    }
}
