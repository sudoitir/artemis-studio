package io.github.sudoitir.artemisstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code .../rr/*} — expectation CRUD, flows, and stats (request-reply-tracing spec). */
class RequestReplyControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void tearDown() {
        clusters.deleteById(clusterId);
    }

    @Test
    void createListUpdateAndDeleteAnExpectation() throws Exception {
        String body = """
                {"requestAddress":"rr.request","replyAddress":"rr.reply","samplePerMin":10,"capturePayload":false}""";

        String created = mvc.perform(post("/api/v1/clusters/{id}/rr/expectations", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestAddress").value("rr.request"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(get("/api/v1/clusters/{id}/rr/expectations", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestAddress").value("rr.request"));

        mvc.perform(delete("/api/v1/clusters/{id}/rr/expectations/{eid}", clusterId, id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/clusters/{id}/rr/expectations", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void flowsPageIsEmptyForANewCluster() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/rr/flows", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void statsResponseHasNoAddressesForAClusterWithNoFlows() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/rr/stats", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses").isEmpty());
    }
}
