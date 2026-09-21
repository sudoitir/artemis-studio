package io.github.sudoitir.artemisstudio.kernel.audit.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditScope;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../audit} — filtered, newest-first paging over `audit_event`. */
@org.junit.jupiter.api.extension.ExtendWith(AdminAuthenticationExtension.class)
class AuditControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    AuditService auditService;

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
                action, "QUEUE", "Q", "anonymous", "req", null, null, clusterId, "prod", null, null, dryRun);
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
    void aChildNamesItsParentAndTheParentFilterListsTheChildren() throws Exception {
        AuditEvent parent =
                auditService.begin(Actor.system(), "bulk.pause", "QUEUE", "3 queues", clusterId, null, Map.of(), false);
        ScopedValue.where(AuditScope.PARENT, parent.getId())
                .run(() -> auditService.begin(
                        Actor.system(), "PAUSE_QUEUE", "QUEUE", "orders", clusterId, null, Map.of(), false));

        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("parentId", parent.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].action").value("PAUSE_QUEUE"))
                .andExpect(jsonPath("$.data[0].parentId").value(parent.getId()));
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("action", "bulk.pause"))
                .andExpect(jsonPath("$.data[0].id").value(parent.getId()))
                .andExpect(jsonPath("$.data[0].parentId").doesNotExist());
    }

    @Test
    void aFutureTimeRangeNarrowsToNothing() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
