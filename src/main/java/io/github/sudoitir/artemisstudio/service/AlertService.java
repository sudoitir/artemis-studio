package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.mapper.AlertViewMapper;
import io.github.sudoitir.artemisstudio.persist.AlertFiringEntity;
import io.github.sudoitir.artemisstudio.persist.AlertFiringRepository;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleRepository;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertFiringPageView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertFiringView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.ClusterFiringCountView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of alert firings — current and historical (alerting spec). */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertFiringRepository firingRepo;
    private final AlertRuleRepository ruleRepo;
    private final AlertViewMapper mapper;

    @Transactional(readOnly = true)
    public List<AlertFiringView> firingNow(UUID clusterId) {
        List<AlertFiringEntity> open = firingRepo.findByClusterIdAndResolvedAtIsNullOrderByStartedAtDesc(clusterId);
        Map<UUID, String> names = ruleNames(open);
        return open.stream()
                .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertFiringPageView history(UUID clusterId, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        var result = firingRepo.findByClusterIdOrderBySeqDesc(clusterId, PageRequest.of(p - 1, s));
        Map<UUID, String> names = ruleNames(result.getContent());
        return new AlertFiringPageView(
                result.getContent().stream()
                        .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                        .toList(),
                result.getTotalElements(),
                p,
                s);
    }

    /** Cross-cluster open-firing counts for the shell badge — no cluster context required. */
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
