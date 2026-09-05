package io.github.sudoitir.artemisstudio.mapper;

import io.github.sudoitir.artemisstudio.persist.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertFiringView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.NotificationChannelView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Hand-written, not MapStruct (ADR-0014's escape hatch, per {@code QueueViewMapper}):
 * {@link AlertRuleView} needs a rule's bound channel ids alongside its own
 * columns, which a generated one-entity-to-one-record mapper cannot aggregate.
 */
@Component
public class AlertViewMapper {

    public AlertRuleView rule(AlertRuleEntity e, List<UUID> channelIds) {
        return new AlertRuleView(
                e.getId(),
                e.getClusterId(),
                e.getName(),
                e.getKind(),
                e.getMetric(),
                e.getComparator(),
                e.getThreshold(),
                e.getStateCondition(),
                e.getForSeconds(),
                e.getSeverity(),
                e.getScope(),
                e.isEnabled(),
                channelIds,
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public AlertFiringView firing(AlertFiringEntity e, String ruleName) {
        return new AlertFiringView(
                e.getSeq(),
                e.getRuleId(),
                ruleName,
                e.getSubjectKey(),
                e.getSeverity(),
                e.getValue(),
                e.getStartedAt(),
                e.getResolvedAt());
    }

    public NotificationChannelView channel(NotificationChannelEntity e) {
        return new NotificationChannelView(
                e.getId(), e.getName(), e.getKind(), e.getConfig(), e.isEnabled(), e.getSecretCt() != null);
    }
}
