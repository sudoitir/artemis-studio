package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.persist.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.persist.AlertStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link AlertEvaluator} evaluating a real threshold rule against real
 * {@code queue_snapshot} rows (design.md decisions 3-4). Exercises the wiring
 * an isolated {@code AlertStateMachineTest} cannot: rule lookup, subject
 * discovery, and the {@code alert_state}/{@code alert_firing} writes.
 */
class AlertEvaluatorIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    AlertEvaluator evaluator;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertStateRepository states;

    @Autowired
    AlertFiringRepository firings;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void snapshot(UUID clusterId, UUID nodeId, String queueName, long messageCount) {
        jdbc.update("""
                INSERT INTO queue_snapshot (node_id, queue_name, cluster_id, address, routing_type, message_count)
                VALUES (:n, :q, :c, :q, 'ANYCAST', :mc)
                ON CONFLICT (node_id, queue_name) DO UPDATE SET message_count = :mc
                """, Map.of("n", nodeId, "q", queueName, "c", clusterId, "mc", messageCount));
    }

    @Test
    void aSustainedBreachFiresAfterTheDebounceAndResolvesWhenItClears() {
        ClusterEntity cluster = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null));
        clusterId = cluster.getId();
        BrokerNodeEntity node = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "STANDALONE", "node-1"));

        AlertRuleEntity rule = rules.save(
                AlertRuleEntity.threshold(clusterId, "Deep queue", "messageCount", "GT", 100.0, 0, "WARNING", null));

        snapshot(clusterId, node.getId(), "orders", 150);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).hasSize(1);
        assertThat(states.findByRuleId(rule.getId()).get(0).getState()).isEqualTo("FIRING");
        assertThat(firings.findByClusterIdAndResolvedAtIsNullOrderByStartedAtDesc(clusterId))
                .hasSize(1);

        snapshot(clusterId, node.getId(), "orders", 10);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).isEmpty(); // resolved back to OK, row dropped
        assertThat(firings.findByClusterIdAndResolvedAtIsNullOrderByStartedAtDesc(clusterId))
                .isEmpty();
    }

    @Test
    void aDisabledRuleNeverEvaluates() {
        ClusterEntity cluster = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null));
        clusterId = cluster.getId();
        BrokerNodeEntity node = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "STANDALONE", "node-1"));

        AlertRuleEntity rule = rules.save(
                AlertRuleEntity.threshold(clusterId, "Deep queue", "messageCount", "GT", 100.0, 0, "WARNING", null));
        rule.setEnabled(false);
        rules.save(rule);

        snapshot(clusterId, node.getId(), "orders", 150);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).isEmpty();
    }
}
