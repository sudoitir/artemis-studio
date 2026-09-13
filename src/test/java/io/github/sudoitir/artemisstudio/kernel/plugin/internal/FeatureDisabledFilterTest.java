package io.github.sudoitir.artemisstudio.kernel.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureDescriptor.Kind;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.InstalledFeatures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class FeatureDisabledFilterTest {

    private final FeatureDisabledFilter filter = new FeatureDisabledFilter(
            new FeatureRegistry(
                    new InstalledFeatures(List.of(FeatureDescriptor.builder()
                            .id("sql")
                            .title("SQL console")
                            .kind(Kind.FEATURE)
                            .apiPrefix("/api/v1/clusters/{clusterId}/sql")
                            .build())),
                    new MockEnvironment().withProperty("artemis-studio.features.sql.enabled", "false")),
            JsonMapper.builder().build());

    @Test
    void aDisabledFeaturesEndpointExplainsItselfWith404() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/clusters/c1/sql/query");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("feature-disabled")
                .contains("\"featureId\":\"sql\"")
                .contains("artemis-studio.features.sql.enabled");
        assertThat(chain.getRequest())
                .as("the request must not reach a handler")
                .isNull();
    }

    @Test
    void otherPathsPassThrough() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/clusters/c1/queues");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
