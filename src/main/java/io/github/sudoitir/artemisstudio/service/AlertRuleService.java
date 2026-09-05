package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.domain.alerting.GaugeCondition;
import io.github.sudoitir.artemisstudio.domain.alerting.RateCondition;
import io.github.sudoitir.artemisstudio.mapper.AlertViewMapper;
import io.github.sudoitir.artemisstudio.persist.AlertRuleChannelEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleChannelRepository;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alert rule CRUD. Every mutation is audited in-transaction, following
 * {@code ClusterService}'s pattern (ADR-0023) — rule/channel changes are
 * operator actions; the firings a rule later produces are not (alerting spec).
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private static final Set<String> STATE_CONDITIONS =
            Set.of("SPLIT_BRAIN", "NODE_DOWN", "REPLICATION_BEHIND", "CLUSTER_DEGRADED");
    private static final Set<String> COMPARATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE");

    private final AlertRuleRepository rules;
    private final AlertRuleChannelRepository ruleChannels;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final AlertViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;

    @Transactional(readOnly = true)
    public List<AlertRuleView> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.ALERT_READ);
        return rules.findByClusterIdOrderByName(clusterId).stream()
                .map(r -> mapper.rule(r, channelIds(r.getId())))
                .toList();
    }

    @Transactional
    public AlertRuleView create(UUID clusterId, AlertRuleRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.ALERT_WRITE);
        AlertRuleEntity rule = validated(request);
        rule.setClusterId(clusterId);
        rules.save(rule);
        bindChannels(rule.getId(), request.channelIds());

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "CREATE_ALERT_RULE",
                "ALERT_RULE",
                rule.getName(),
                clusterId,
                null,
                Map.of("kind", rule.getKind()),
                false);
        audit.succeed(event, 1);
        return mapper.rule(rule, channelIds(rule.getId()));
    }

    @Transactional
    public AlertRuleView update(UUID clusterId, UUID ruleId, AlertRuleRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.ALERT_WRITE);
        AlertRuleEntity existing = requireRule(clusterId, ruleId);
        AlertRuleEntity updated = validated(request);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_ALERT_RULE",
                "ALERT_RULE",
                existing.getName(),
                clusterId,
                null,
                Map.of("kind", updated.getKind()),
                false);

        existing.setName(updated.getName());
        existing.setKind(updated.getKind());
        existing.setMetric(updated.getMetric());
        existing.setComparator(updated.getComparator());
        existing.setThreshold(updated.getThreshold());
        existing.setStateCondition(updated.getStateCondition());
        existing.setForSeconds(updated.getForSeconds());
        existing.setSeverity(updated.getSeverity());
        existing.setScope(updated.getScope());
        existing.setEnabled(request.enabled());
        rules.save(existing);

        ruleChannels.deleteByRuleId(ruleId);
        bindChannels(ruleId, request.channelIds());

        audit.succeed(event, 1);
        return mapper.rule(existing, channelIds(ruleId));
    }

    @Transactional
    public void delete(UUID clusterId, UUID ruleId) {
        clusterAccess.requireCluster(clusterId, Permissions.ALERT_WRITE);
        AlertRuleEntity rule = requireRule(clusterId, ruleId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "DELETE_ALERT_RULE",
                "ALERT_RULE",
                rule.getName(),
                clusterId,
                null,
                Map.of(),
                false);
        rules.delete(rule); // cascades alert_state / alert_firing / alert_delivery / alert_rule_channel
        audit.succeed(event, 1);
    }

    // ---- helpers ------------------------------------------------------------

    private AlertRuleEntity validated(AlertRuleRequest r) {
        if ("METRIC_THRESHOLD".equals(r.kind())) {
            if (r.metric() == null || r.comparator() == null || r.threshold() == null) {
                throw new IllegalArgumentException(
                        "metric, comparator, and threshold are required for a threshold rule");
            }
            if (!COMPARATORS.contains(r.comparator())) {
                throw new IllegalArgumentException("unknown comparator: " + r.comparator());
            }
            if (!GaugeCondition.supports(r.metric()) && !RateCondition.supports(r.metric())) {
                throw new IllegalArgumentException("unknown metric: " + r.metric());
            }
            if (r.stateCondition() != null) {
                throw new IllegalArgumentException("a threshold rule must not set stateCondition");
            }
            return AlertRuleEntity.threshold(
                    null, r.name(), r.metric(), r.comparator(), r.threshold(), r.forSeconds(), r.severity(), r.scope());
        }
        if ("STATE".equals(r.kind())) {
            if (r.stateCondition() == null || !STATE_CONDITIONS.contains(r.stateCondition())) {
                throw new IllegalArgumentException("unknown stateCondition: " + r.stateCondition());
            }
            if (r.metric() != null || r.comparator() != null || r.threshold() != null) {
                throw new IllegalArgumentException("a state rule must not set metric/comparator/threshold");
            }
            return AlertRuleEntity.state(null, r.name(), r.stateCondition(), r.forSeconds(), r.severity());
        }
        throw new IllegalArgumentException("unknown rule kind: " + r.kind());
    }

    private void bindChannels(UUID ruleId, List<UUID> channelIds) {
        if (channelIds == null) {
            return;
        }
        for (UUID channelId : channelIds) {
            ruleChannels.save(new AlertRuleChannelEntity(ruleId, channelId));
        }
    }

    private List<UUID> channelIds(UUID ruleId) {
        return ruleChannels.findByRuleId(ruleId).stream()
                .map(AlertRuleChannelEntity::getChannelId)
                .toList();
    }

    private AlertRuleEntity requireRule(UUID clusterId, UUID ruleId) {
        AlertRuleEntity rule = rules.findById(ruleId).orElseThrow(() -> new NotFoundException("AlertRule", ruleId));
        if (!clusterId.equals(rule.getClusterId())) {
            throw new NotFoundException("AlertRule", ruleId);
        }
        return rule;
    }
}
