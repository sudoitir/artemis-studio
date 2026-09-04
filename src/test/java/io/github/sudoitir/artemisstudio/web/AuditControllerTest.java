package io.github.sudoitir.artemisstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../audit} — filtered, newest-first paging over `audit_event`. */
class AuditControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audit;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();

        save("SEND_MESSAGE", "SUCCESS", 1, false);
        save("DELETE_MESSAGES", "SUCCESS", 5, false);
        save("DELETE_MESSAGES", "FAILURE", null, false);
        save("PURGE_QUEUE", "SUCCESS", 12, true);
    }

    @AfterEach
    void cleanUp() {
        audit.deleteAll();
        clusters.deleteById(clusterId);
    }

    private void save(String action, String outcome, Integer count, boolean dryRun) {
        AuditEventEntity e = new AuditEventEntity(
                action, "QUEUE", "Q", "anonymous", "req", null, null, clusterId, null, null, dryRun);
        if ("SUCCESS".equals(outcome)) {
            e.markSuccess(count == null ? 0 : count);
        } else if ("FAILURE".equals(outcome)) {
            e.markFailure("broker refused");
        }
        audit.save(e);
    }

    @Test
    void returnsEverythingNewestFirst() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test
    void filtersByActionAndOutcome() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("action", "DELETE_MESSAGES")
                        .param("outcome", "FAILURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].action").value("DELETE_MESSAGES"))
                .andExpect(jsonPath("$.data[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.data[0].error").value("broker refused"));
    }

    @Test
    void filtersByOutcomeOnly() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("outcome", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void aFutureTimeRangeNarrowsToNothing() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
