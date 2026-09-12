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
 * Editing a declaration and applying it are separate authorities, and neither
 * follows from being allowed to read the cluster. A caller without the grant gets
 * the same {@code 404} the rest of the API gives, so a config route cannot be used
 * to probe for cluster ids either.
 */
class BrokerConfigAuthorizationTest extends PostgresIntegrationTest {

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

    private UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        StudioPrincipal principal = new StudioPrincipal(
                null, "scoped", Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static final String SAVE_BODY = """
            {"document":{"version":1,"addresses":[],"addressSettings":[{"match":"orders.#","values":{"maxSizeBytes":1048576}}],"securitySettings":[],"diverts":[]},"expectedRevision":null,"note":"t"}""";

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void readingTheDeclarationNeedsOnlyClusterRead() throws Exception {
        assertThat(status(MockMvcRequestBuilders.get("/api/v1/clusters/" + clusterId + "/config")
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))))
                .isEqualTo(200);
    }

    @Test
    void savingNeedsConfigWrite() throws Exception {
        assertThat(status(MockMvcRequestBuilders.put("/api/v1/clusters/" + clusterId + "/config")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAVE_BODY)))
                .isEqualTo(404);
        assertThat(status(MockMvcRequestBuilders.put("/api/v1/clusters/" + clusterId + "/config")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.CONFIG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAVE_BODY)))
                .isEqualTo(201);
    }

    @Test
    void writeDoesNotImplyApply() throws Exception {
        // A declaration is harmless until something applies it; the authorities differ.
        assertThat(status(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/config/apply?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.CONFIG_WRITE)))))
                .isEqualTo(404);
    }

    @Test
    void applyDoesNotImplyWrite() throws Exception {
        assertThat(status(MockMvcRequestBuilders.put("/api/v1/clusters/" + clusterId + "/config")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.CONFIG_APPLY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAVE_BODY)))
                .isEqualTo(404);
    }

    @Test
    void importAndAdoptNeedConfigWrite() throws Exception {
        assertThat(status(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/config/import-xml")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<core/>")))
                .isEqualTo(404);
        assertThat(status(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/config/adopt")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))))
                .isEqualTo(404);
    }

    @Test
    void aCallerWithoutTheGrantCannotTellARealClusterFromAnInventedOne() throws Exception {
        var caller = callerWith(Permissions.CLUSTER_READ);
        int real = status(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/config/apply?dryRun=true")
                .with(csrf())
                .with(authentication(caller)));
        int fake = status(
                MockMvcRequestBuilders.post("/api/v1/clusters/" + UUID.randomUUID() + "/config/apply?dryRun=true")
                        .with(csrf())
                        .with(authentication(caller)));
        assertThat(real).isEqualTo(404);
        assertThat(fake).isEqualTo(real);
    }

    @Test
    void declaringTheRecommendationsNeedsConfigWrite() throws Exception {
        // Reading them is a read; turning them into a revision is an edit. A caller
        // who can only read must not be able to move the declaration.
        assertThat(status(
                        MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/config/recommendations/declare")
                                .with(csrf())
                                .with(authentication(callerWith(Permissions.CLUSTER_READ)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")))
                .isEqualTo(404);
    }

    @Test
    void anInvalidDeclarationIsRefusedWithTheFieldNamed() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.put("/api/v1/clusters/" + clusterId + "/config")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ, Permissions.CONFIG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"document":{"version":1,"addresses":[],"addressSettings":[{"match":"orders.#","values":{"addressFullMessagePolicy":"EXPLODE"}}],"securitySettings":[],"diverts":[]}}"""))
                .andReturn()
                .getResponse();
        assertThat(result.getStatus()).isEqualTo(400);
        assertThat(result.getContentAsString()).contains("config-invalid").contains("addressFullMessagePolicy");
    }
}
