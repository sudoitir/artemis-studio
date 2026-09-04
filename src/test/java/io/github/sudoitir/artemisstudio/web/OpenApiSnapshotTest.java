package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regenerates {@code web/openapi.json} from the live springdoc document and fails
 * if the committed snapshot is stale. This is how ADR-0019's OpenAPI generation
 * works without a running server in the build: the snapshot is a test artifact,
 * {@code npm run gen:api} turns it into {@code web/src/api/schema.d.ts}, and CI's
 * {@code git diff --exit-code} catches any undeclared contract drift.
 *
 * <p>Keys are sorted on write so the diff is stable regardless of springdoc's
 * internal ordering.
 */
class OpenApiSnapshotTest extends PostgresIntegrationTest {

    private static final Path SNAPSHOT = Path.of("web", "openapi.json");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Autowired
    WebApplicationContext webContext;

    @Test
    void openApiSnapshotIsCurrent() throws Exception {
        MockMvc mvc = webAppContextSetup(webContext).build();

        String body = mvc.perform(get("/v3/api-docs").accept("application/json"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // Re-serialise through a key-sorted mapper so ordering never causes a diff.
        JsonNode tree = mapper.readTree(body);
        String pretty = mapper.writeValueAsString(tree) + "\n";

        String existing = Files.exists(SNAPSHOT) ? Files.readString(SNAPSHOT, StandardCharsets.UTF_8) : null;
        if (!pretty.equals(existing)) {
            Files.writeString(SNAPSHOT, pretty, StandardCharsets.UTF_8);
        }

        assertThat(pretty)
                .as("web/openapi.json is stale — it has been rewritten; run `npm --prefix web run gen:api`, "
                        + "review the schema.d.ts diff, and commit both")
                .isEqualTo(existing);
    }
}
