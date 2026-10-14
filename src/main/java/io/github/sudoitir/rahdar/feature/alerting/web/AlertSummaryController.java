package io.github.sudoitir.rahdar.feature.alerting.web;

import io.github.sudoitir.rahdar.feature.alerting.AlertService;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.ClusterFiringCountView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cross-cluster open-firing counts — the app shell's firing badge (alerting spec). */
@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class AlertSummaryController {

    private final AlertService alerts;

    @GetMapping("/firing")
    public PagedView<ClusterFiringCountView> firing(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(alerts.firingCounts(), null);
    }
}
