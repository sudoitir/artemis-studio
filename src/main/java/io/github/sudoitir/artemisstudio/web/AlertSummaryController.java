package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.AlertService;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.ClusterFiringCountView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cross-cluster open-firing counts — the app shell's firing badge (alerting spec). */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertSummaryController {

    private final AlertService alerts;

    @GetMapping("/firing")
    public List<ClusterFiringCountView> firing() {
        return alerts.firingCounts();
    }
}
