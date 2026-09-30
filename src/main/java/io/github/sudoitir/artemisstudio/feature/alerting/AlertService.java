package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertFiringPageView;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertFiringView;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.ClusterFiringCountView;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
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

    @Transactional(readOnly = true)
    public List<AlertFiringView> firingNow(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        List<AlertFiringEntity> open = firingRepo.findOpenVisible(clusterId, perm.can(AlertPermissions.ALERT_READ));
        Map<UUID, String> names = ruleNames(open);
        return open.stream()
                .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertFiringPageView history(UUID clusterId, int page, int size) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        var result = firingRepo.findVisible(clusterId, perm.can(AlertPermissions.ALERT_READ), PageRequest.of(p - 1, s));
        Map<UUID, String> names = ruleNames(result.getContent());
        return new AlertFiringPageView(
                result.getContent().stream()
                        .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                        .toList(),
                result.getTotalElements(),
                p,
                s);
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
                .map(c -> new ClusterFiringCountView(c.getClusterId(), c.getFiring()))
                .toList();
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
