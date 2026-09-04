package io.github.sudoitir.artemisstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../events} — filtered, newest-first paging over {@code broker_event}. */
class EventControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();

        event("CONSUMER_CREATED", "orders", 3);
        event("CONSUMER_CLOSED", "orders", 2);
        event("SESSION_CREATED", "payments", 1);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId));
        clusters.deleteById(clusterId);
    }

    private void event(String type, String address, int ageMinutes) {
        jdbc.update("""
                INSERT INTO broker_event (occurred_at, type, address, cluster_id)
                VALUES (now() - make_interval(mins => :age), :type, :addr, :c)
                """, Map.of("age", ageMinutes, "type", type, "addr", address, "c", clusterId));
    }

    @Test
    void returnsEverythingNewestFirstWithEnvelope() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].type").value("SESSION_CREATED"))
                .andExpect(jsonPath("$.dropped").value(0));
    }

    @Test
    void filtersByType() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("type", "CONSUMER_CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].type").value("CONSUMER_CREATED"));
    }

    @Test
    void filtersByAddress() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("address", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void aFutureTimeRangeNarrowsToNothing() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
