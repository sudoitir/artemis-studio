package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertFiringView;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.ClusterFiringCountView;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of alert firings — current and historical (alerting spec). A cluster's view also holds
 * the installation's firings (ADR-0135), for a caller with the global grant.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertFiringRepository firingRepo;
    private final AlertRuleRepository ruleRepo;
    private final AlertViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver perm;
    private final AlertRuleService ruleService;

    @Transactional(readOnly = true)
    public List<AlertFiringView> firingNow(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        boolean everything = clusterAccess.holds(clusterId, Permissions.QUEUE_READ);
        List<AlertFiringEntity> open = firingRepo.findOpenVisible(
                clusterId, perm.can(AlertPermissions.ALERT_READ), everything, visibleRuleIds(clusterId, everything));
        Map<UUID, String> names = ruleNames(open);
        return open.stream()
                .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedView<AlertFiringView> history(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        boolean everything = clusterAccess.holds(clusterId, Permissions.QUEUE_READ);
        var result = firingRepo.findVisible(
                clusterId,
                perm.can(AlertPermissions.ALERT_READ),
                everything,
                visibleRuleIds(clusterId, everything),
                PageRequest.of(query.page() - 1, query.size()));
        Map<UUID, String> names = ruleNames(result.getContent());
        return PagedView.of(result, f -> mapper.firing(f, names.get(f.getRuleId())));
    }

    /**
     * Cross-cluster open-firing counts for the shell badge, filtered to what the caller may see.
     * The installation's count has a null cluster, which the filter passes only for the global grant.
     */
    @PostFilter(
            "@perm.can(filterObject.clusterId(), T(io.github.sudoitir.artemisstudio.feature.alerting.AlertPermissions).ALERT_READ)")
    @Transactional(readOnly = true)
    public List<ClusterFiringCountView> firingCounts() {
        return firingRepo.firingCountsByCluster().stream()
                .map(c -> new ClusterFiringCountView(c.getClusterId(), visibleCount(c.getClusterId(), c.getFiring())))
                .toList();
    }

    /** A cluster's count of firings, less those of rules the caller may not see; the installation's is unchanged. */
    private long visibleCount(UUID clusterId, long firing) {
        if (clusterId == null
                || !perm.can(clusterId, AlertPermissions.ALERT_READ)
                || clusterAccess.holds(clusterId, Permissions.QUEUE_READ)) {
            return firing;
        }
        return firingRepo.countByClusterIdAndResolvedAtIsNullAndRuleIdIn(clusterId, visibleRuleIds(clusterId, false));
    }

    /**
     * The cluster's rules the caller may see, whose firings they may see: all of them for a caller who reads
     * every queue. Never empty, as an empty collection cannot be bound.
     */
    private List<UUID> visibleRuleIds(UUID clusterId, boolean everything) {
        if (everything) {
            return List.of(new UUID(0, 0));
        }
        List<UUID> ids = ruleRepo.findByClusterId(clusterId).stream()
                .filter(r -> ruleService.seen(clusterId, r))
                .map(AlertRuleEntity::getId)
                .toList();
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private Map<UUID, String> ruleNames(List<AlertFiringEntity> firings) {
        List<UUID> ruleIds =
                firings.stream().map(AlertFiringEntity::getRuleId).distinct().toList();
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return ruleRepo.findAllById(ruleIds).stream()
                .collect(java.util.stream.Collectors.toMap(AlertRuleEntity::getId, AlertRuleEntity::getName));
    }
}
