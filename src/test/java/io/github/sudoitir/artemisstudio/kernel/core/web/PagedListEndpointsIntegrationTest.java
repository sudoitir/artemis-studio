package io.github.sudoitir.artemisstudio.kernel.core.web;

import static io.github.sudoitir.artemisstudio.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every list this API pages answers in the one envelope, takes {@code page} and {@code size}, and refuses a
 * value out of range (public-api spec, ADR-0147). The contract check validates each body's shape as well.
 */
class PagedListEndpointsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID NO_CLUSTER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    WebApplicationContext webContext;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        StudioPrincipal admin = new StudioPrincipal(
                UUID.randomUUID(),
                "pager",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))),
                false);
        return request.with(
                authentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities())));
    }

    /** Lists that always hold several entries: a path, and no query of its own. */
    static Stream<Arguments> populated() {
        return Stream.of(
                Arguments.of("/api/v1/roles"),
                Arguments.of("/api/v1/permissions"),
                Arguments.of("/api/v1/mcp/tools"),
                Arguments.of("/api/v1/system/jobs"));
    }

    /** Lists that may be empty here, but are still an envelope. */
    static Stream<Arguments> possiblyEmpty() {
        return Stream.of(
                Arguments.of("/api/v1/environments"), Arguments.of("/api/v1/clusters/" + NO_CLUSTER + "/sql/index"));
    }

    @ParameterizedTest
    @MethodSource("populated")
    void aPopulatedListIsPagedByPageAndSize(String path) throws Exception {
        String first = mvc.perform(as(get(path).param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int count = JsonPath.read(first, "$.count");
        assertThat(count).isGreaterThan(1);

        String second = mvc.perform(as(get(path).param("size", "1").param("page", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((Object) JsonPath.read(second, "$.data[0]")).isNotEqualTo(JsonPath.read(first, "$.data[0]"));

        String beyond = mvc.perform(as(get(path).param("size", "1").param("page", String.valueOf(count + 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((List<?>) JsonPath.read(beyond, "$.data")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("possiblyEmpty")
    void anyListIsTheEnvelope(String path) throws Exception {
        mvc.perform(as(get(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").isNumber())
                .andExpect(jsonPath("$.hasNext").isBoolean());
    }

    @ParameterizedTest
    @MethodSource("populated")
    void aPageOrSizeOutOfRangeIsRefused(String path) throws Exception {
        for (String[] bad : new String[][] {{"size", "0"}, {"size", "501"}, {"page", "0"}}) {
            mvc.perform(as(get(path).param(bad[0], bad[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://artemis-studio.dev/problems/invalid-value"));
        }
    }
}
