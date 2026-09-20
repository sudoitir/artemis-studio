package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertStateMachine.Transition;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleChannelRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertStateEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertStateRepository;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates one cluster's rules of one kind (design.md decision 3) — called
 * by {@link AlertScrapeListener} right after the scrape tier that kind's data
 * source depends on has persisted, never from an independent timer. DB-only:
 * reads already-persisted {@code queue_snapshot}/{@code metric_sample}/HA state
 * and writes {@code alert_state}/{@code alert_firing}/{@code alert_delivery} —
 * no broker I/O, preserving ADR-0015's rule that network calls never share a
 * transaction with persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEvaluator {

    private final AlertRuleRepository rules;
    private final AlertStateRepository states;
    private final AlertFiringRepository firings;
    private final AlertDeliveryRepository deliveries;
    private final AlertRuleChannelRepository ruleChannels;
    /**
     * Every rule kind's predicate, in {@code @Order} sequence — derived metrics ahead of
     * the raw gauge and rate conditions, which match on broader sets. Injected as a list
     * rather than named one by one so a module that may depend on alerting can contribute
     * a kind without this class knowing its type (ADR-0089).
     */
    private final List<AlertCondition> conditions;

    private final SseHub hub;
    private final ObjectMapper mapper;

    @Transactional
    public void evaluate(UUID clusterId, String kind) {
        List<AlertRuleEntity> enabled = rules.findByClusterIdAndKindAndEnabledTrue(clusterId, kind);
        boolean anyTransition = false;
        for (AlertRuleEntity rule : enabled) {
            AlertRuleSpec spec = specOf(rule);
            AlertCondition condition = conditionFor(spec);
            if (condition == null) {
                continue;
            }
            Evaluation evaluation = condition.evaluate(clusterId, spec);
            List<Transition> transitions = process(rule, evaluation);
            if (!transitions.isEmpty()) {
                anyTransition = true;
                enqueueDelivery(rule, transitions);
            }
        }
        if (anyTransition) {
            hub.publish(clusterId, "alerts");
        }
    }

    private AlertCondition conditionFor(AlertRuleSpec rule) {
        for (AlertCondition condition : conditions) {
            if (condition.supports(rule)) {
                return condition;
            }
        }
        log.warn("Alert rule {} has an unrecognised metric '{}'; skipping", rule.id(), rule.metric());
        return null;
    }

    /** The exported projection a condition evaluates, so none of them sees the entity. */
    static AlertRuleSpec specOf(AlertRuleEntity rule) {
        return new AlertRuleSpec(
                rule.getId(),
                rule.isThreshold(),
                rule.getMetric(),
                rule.getComparator(),
                rule.getThreshold(),
                rule.getScope(),
                rule.getStateCondition());
    }

    private List<Transition> process(AlertRuleEntity rule, Evaluation evaluation) {
        Instant now = Instant.now();
        Map<String, AlertStateEntity> bySubject = new HashMap<>();
        for (AlertStateEntity s : states.findByRuleId(rule.getId())) {
            bySubject.put(s.getSubjectKey(), s);
        }

        List<Transition> transitions = new ArrayList<>();
        for (String subject : evaluation.universe()) {
            AlertStateEntity state = bySubject.remove(subject);
            boolean isNew = state == null;
            if (isNew) {
                state = new AlertStateEntity(rule.getId(), subject);
            }
            boolean active = evaluation.active().containsKey(subject);
            Double value = evaluation.active().get(subject);

            Transition t = AlertStateMachine.advance(state, active, value, rule.getForSeconds(), now);
            if (!"OK".equals(state.getState())) {
                states.save(state);
            } else if (!isNew) {
                states.delete(state);
            }
            if (t != null) {
                transitions.add(t);
                recordHistory(rule, t, now);
            }
        }

        // Subjects with a tracked state that vanished from this tick's universe
        // (e.g. a deleted queue) resolve rather than being left stuck forever.
        for (AlertStateEntity orphan : bySubject.values()) {
            if ("FIRING".equals(orphan.getState())) {
                Transition t = new Transition(orphan.getSubjectKey(), TransitionKind.RESOLVED, orphan.getLastValue());
                transitions.add(t);
                recordHistory(rule, t, now);
            }
            states.delete(orphan);
        }
        return transitions;
    }

    private void recordHistory(AlertRuleEntity rule, Transition t, Instant now) {
        if (t.kind() == TransitionKind.FIRED) {
            firings.save(new AlertFiringEntity(
                    rule.getClusterId(), rule.getId(), t.subjectKey(), rule.getSeverity(), t.value(), now));
        } else {
            firings.findFirstByRuleIdAndSubjectKeyAndResolvedAtIsNull(rule.getId(), t.subjectKey())
                    .ifPresent(f -> {
                        f.resolve(now);
                        firings.save(f);
                    });
        }
    }

    /** One delivery row per bound channel, carrying every transition this tick produced for the rule. */
    private void enqueueDelivery(AlertRuleEntity rule, List<Transition> transitions) {
        List<UUID> channelIds = ruleChannels.findByRuleId(rule.getId()).stream()
                .map(rc -> rc.getChannelId())
                .toList();
        if (channelIds.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ruleId", rule.getId());
        payload.put("ruleName", rule.getName());
        payload.put("severity", rule.getSeverity());
        payload.put(
                "transitions",
                transitions.stream()
                        .map(t -> Map.of(
                                "subject", t.subjectKey(), "kind", t.kind().name(), "value", t.value()))
                        .toList());
        String json = mapper.writeValueAsString(payload);
        for (UUID channelId : channelIds) {
            deliveries.save(new AlertDeliveryEntity(rule.getId(), channelId, json));
        }
    }
}
