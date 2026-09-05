package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code 013-alerting.sql} against a real Postgres: the discriminated
 * {@code alert_rule} shape, the evaluator's index, and the dispatcher's
 * {@code FOR UPDATE SKIP LOCKED} claim query (ADR-0035, ADR-0036).
 */
class AlertSchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    NotificationChannelRepository channels;

    @Autowired
    AlertDeliveryRepository deliveries;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;
    private final List<UUID> createdChannels = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
        createdChannels.forEach(channels::deleteById);
    }

    private NotificationChannelEntity channel(String namePrefix, String kind) {
        NotificationChannelEntity c = channels.save(
                new NotificationChannelEntity(namePrefix + "-" + UUID.randomUUID(), kind, "{}", null, null));
        createdChannels.add(c.getId());
        return c;
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void thresholdAndStateRulesBothPersistAndAreFilterableByKind() {
        UUID c = cluster();
        rules.save(AlertRuleEntity.threshold(c, "Deep queue", "messageCount", "GT", 1000.0, 60, "WARNING", null));
        rules.save(AlertRuleEntity.state(c, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));

        List<AlertRuleEntity> thresholds = rules.findByClusterIdAndKindAndEnabledTrue(c, "METRIC_THRESHOLD");
        List<AlertRuleEntity> states = rules.findByClusterIdAndKindAndEnabledTrue(c, "STATE");

        assertThat(thresholds).hasSize(1);
        assertThat(states).hasSize(1);
    }

    @Test
    void kindShapeCheckRejectsAThresholdRuleWithAStateCondition() {
        UUID c = cluster();
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO alert_rule (cluster_id, kind, metric, comparator, threshold, state_condition, name)
                        VALUES (:c, 'METRIC_THRESHOLD', 'messageCount', 'GT', 1, 'SPLIT_BRAIN', 'bad')
                        """, java.util.Map.of("c", c)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notificationChannelNameIsUnique() {
        String name = "ops-" + UUID.randomUUID();
        NotificationChannelEntity first = channels.save(new NotificationChannelEntity(name, "SLACK", "{}", null, null));
        createdChannels.add(first.getId());
        assertThatThrownBy(() -> channels.save(new NotificationChannelEntity(name, "WEBHOOK", "{}", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimDueOnlyReturnsPendingDeliveriesAtOrBeforeNow() {
        UUID c = cluster();
        AlertRuleEntity rule = rules.save(AlertRuleEntity.state(c, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));
        NotificationChannelEntity channel = channel("ops", "SLACK");

        AlertDeliveryEntity due = deliveries.save(new AlertDeliveryEntity(rule.getId(), channel.getId(), "{}"));
        AlertDeliveryEntity notYetDue = deliveries.save(new AlertDeliveryEntity(rule.getId(), channel.getId(), "{}"));
        notYetDue.setNextAttemptAt(Instant.now().plusSeconds(600));
        deliveries.save(notYetDue);

        List<AlertDeliveryEntity> claimed = deliveries.claimDue(10);

        assertThat(claimed).extracting(AlertDeliveryEntity::getSeq).contains(due.getSeq());
        assertThat(claimed).extracting(AlertDeliveryEntity::getSeq).doesNotContain(notYetDue.getSeq());
    }
}
