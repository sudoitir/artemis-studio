package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor.Kind;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The manifest lists every built-in module with its enabled flag and attributes
 * the permission catalogue to enabled modules only. Authentication is enforced by
 * the filter chain and swept for every endpoint by {@code EndpointProtectionTest}.
 */
class ManifestControllerTest {

    @Test
    void listsEnabledAndDisabledFeaturesAndCataloguesOnlyEnabledPermissions() throws Exception {
        var env = new MockEnvironment().withProperty("artemis-studio.features.sql.enabled", "false");
        var registry = new FeatureRegistry(
                new InstalledFeatures(List.of(
                        FeatureDescriptor.builder()
                                .id("queues")
                                .title("Queues")
                                .kind(Kind.FEATURE)
                                .permission(new PermissionDef("queue:create", "Create queues"))
                                .streamTopic(TopicDef.signal("queues"))
                                .build(),
                        FeatureDescriptor.builder()
                                .id("sql")
                                .title("SQL console")
                                .kind(Kind.FEATURE)
                                .permission(new PermissionDef("capture:write", "Turn capture on"))
                                .build())),
                env);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ManifestController(
                        registry, new StaticListableBeanFactory().getBeanProvider(IdentityProviderListing.class)))
                .build();

        mvc.perform(get("/api/v1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract").value(1))
                .andExpect(jsonPath("$.features.length()").value(2))
                .andExpect(jsonPath("$.features[0].id").value("queues"))
                .andExpect(jsonPath("$.features[0].enabled").value(true))
                .andExpect(jsonPath("$.features[0].topics[0]").value("queues"))
                .andExpect(jsonPath("$.features[1].id").value("sql"))
                .andExpect(jsonPath("$.features[1].enabled").value(false))
                .andExpect(jsonPath("$.features[1].enabledProperty").value("artemis-studio.features.sql.enabled"))
                .andExpect(jsonPath("$.features[1].permissions[0]").value("capture:write"))
                .andExpect(jsonPath("$.permissionCatalogue.length()").value(1))
                .andExpect(jsonPath("$.permissionCatalogue[0].action").value("queue:create"))
                .andExpect(jsonPath("$.permissionCatalogue[0].featureId").value("queues"))
                .andExpect(jsonPath("$.identityProviders.length()").value(0));
    }
}
