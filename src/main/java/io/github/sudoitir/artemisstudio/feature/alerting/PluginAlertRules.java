package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleEntity.Condition;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterRegistered;
import io.github.sudoitir.artemisstudio.platform.clusters.RegisteredCluster;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a plugin's declared {@code alertRules} (ADR-0113) on every cluster when the plugin
 * attaches, and on a cluster registered while it runs. {@code alert_rule_seed} remembers each
 * seeded key, so a rule is created once: an operator's edit or deletion is never undone, by an
 * update, a restart or a reinstall. Like the built-in rules, a seeded rule has no channel until
 * an operator routes it.
 */
@Component
@RequiredArgsConstructor
class PluginAlertRules implements PluginBridge {

    private final AlertRuleRepository rules;
    private final ClusterDirectory clusters;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private final Map<String, PluginHandle> attached = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        attached.put(handle.id(), handle);
        for (RegisteredCluster cluster : clusters.clusters()) {
            seed(cluster.getId(), handle.descriptor());
        }
    }

    @Override
    public void detach(PluginHandle handle) {
        attached.remove(handle.id(), handle);
    }

    @EventListener
    void onClusterRegistered(ClusterRegistered event) {
        attached.values().forEach(h -> seed(event.clusterId(), h.descriptor()));
    }

    private void seed(UUID clusterId, PluginDescriptor descriptor) {
        for (PluginDescriptor.AlertRule rule : descriptor.alertRules()) {
            tx.executeWithoutResult(status -> {
                int claimed = jdbc.update(
                        "INSERT INTO alert_rule_seed (plugin_id, seed_key, cluster_id) VALUES (?, ?, ?)"
                                + " ON CONFLICT DO NOTHING",
                        descriptor.id(),
                        rule.key(),
                        clusterId);
                if (claimed == 1) {
                    rules.save(AlertRuleEntity.threshold(
                            clusterId,
                            rule.name(),
                            new Condition(rule.metric(), rule.comparator(), rule.threshold()),
                            rule.forSeconds(),
                            rule.severity(),
                            null));
                }
            });
        }
    }
}
