package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
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

/** Rule CRUD + audit trail (alerting spec, audit-log spec). */
class AlertsControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

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
    void createUpdateAndDeleteAThresholdRuleIsAudited() throws Exception {
        String body = """
                {"name":"Deep queue","kind":"METRIC_THRESHOLD","metric":"messageCount","comparator":"GT",
                 "threshold":1000,"forSeconds":60,"severity":"WARNING","enabled":true,"channelIds":[]}""";

        String created = mvc.perform(post("/api/v1/clusters/{id}/alerts/rules", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("METRIC_THRESHOLD"))
                .andExpect(jsonPath("$.metric").value("messageCount"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(get("/api/v1/clusters/{id}/alerts/rules", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Deep queue"));

        String updateBody = """
                {"name":"Deep queue v2","kind":"METRIC_THRESHOLD","metric":"messageCount","comparator":"GT",
                 "threshold":2000,"forSeconds":30,"severity":"CRITICAL","enabled":true,"channelIds":[]}""";
        mvc.perform(put("/api/v1/clusters/{id}/alerts/rules/{rid}", clusterId, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(2000.0));

        mvc.perform(delete("/api/v1/clusters/{id}/alerts/rules/{rid}", clusterId, id))
                .andExpect(status().isNoContent());

        assertThat(auditEvents.findByClusterIdOrderByTsDesc(clusterId)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectsARuleWithBothMetricAndStateCondition() throws Exception {
        String body = """
                {"name":"bad","kind":"METRIC_THRESHOLD","metric":"messageCount","comparator":"GT",
                 "threshold":1,"stateCondition":"SPLIT_BRAIN","forSeconds":0,"severity":"WARNING",
                 "enabled":true,"channelIds":[]}""";

        mvc.perform(post("/api/v1/clusters/{id}/alerts/rules", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsAStateConditionRule() throws Exception {
        String body = """
                {"name":"Split-brain","kind":"STATE","stateCondition":"SPLIT_BRAIN","forSeconds":0,
                 "severity":"CRITICAL","enabled":true,"channelIds":[]}""";

        mvc.perform(post("/api/v1/clusters/{id}/alerts/rules", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stateCondition").value("SPLIT_BRAIN"))
                .andExpect(jsonPath("$.metric").doesNotExist());
    }
}
