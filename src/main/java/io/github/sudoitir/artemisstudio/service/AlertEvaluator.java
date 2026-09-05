package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.domain.alerting.AlertCondition;
import io.github.sudoitir.artemisstudio.domain.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.artemisstudio.domain.alerting.AlertStateMachine;
import io.github.sudoitir.artemisstudio.domain.alerting.AlertStateMachine.Transition;
import io.github.sudoitir.artemisstudio.domain.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.artemisstudio.domain.alerting.GaugeCondition;
import io.github.sudoitir.artemisstudio.domain.alerting.RateCondition;
import io.github.sudoitir.artemisstudio.domain.alerting.StateCondition;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.persist.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.persist.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.persist.AlertRuleChannelRepository;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.persist.AlertStateEntity;
import io.github.sudoitir.artemisstudio.persist.AlertStateRepository;
import io.github.sudoitir.artemisstudio.sse.SseHub;
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
 * inline from {@code ScrapeScheduler} right after the tier that kind's data
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
    private final GaugeCondition gaugeCondition;
    private final RateCondition rateCondition;
    private final StateCondition stateCondition;
    private final SseHub hub;
    private final ObjectMapper mapper;

    @Transactional
    public void evaluate(UUID clusterId, String kind) {
        List<AlertRuleEntity> enabled = rules.findByClusterIdAndKindAndEnabledTrue(clusterId, kind);
        boolean anyTransition = false;
        for (AlertRuleEntity rule : enabled) {
            AlertCondition condition = conditionFor(rule);
            if (condition == null) {
                continue;
            }
            Evaluation evaluation = condition.evaluate(clusterId, rule);
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

    private AlertCondition conditionFor(AlertRuleEntity rule) {
        if (!rule.isThreshold()) {
            return stateCondition;
        }
        if (GaugeCondition.supports(rule.getMetric())) {
            return gaugeCondition;
        }
        if (RateCondition.supports(rule.getMetric())) {
            return rateCondition;
        }
        log.warn("Alert rule {} has an unrecognised metric '{}'; skipping", rule.getId(), rule.getMetric());
        return null;
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
