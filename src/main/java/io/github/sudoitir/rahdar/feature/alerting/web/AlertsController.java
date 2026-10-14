package io.github.sudoitir.rahdar.feature.alerting.web;

import io.github.sudoitir.rahdar.feature.alerting.AlertRuleService;
import io.github.sudoitir.rahdar.feature.alerting.AlertService;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertFiringView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleRequest;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.PluginMetricView;
import io.github.sudoitir.rahdar.kernel.core.Cursor;
import io.github.sudoitir.rahdar.kernel.core.HistoryPage;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Alert firings, history, and rule CRUD for one cluster (alerting spec). */
@RestController
@RequestMapping("/clusters/{clusterId}/alerts")
@RequiredArgsConstructor
public class AlertsController {

    private final AlertService alerts;
    private final AlertRuleService ruleService;

    @GetMapping("/firing")
    public PagedView<AlertFiringView> firing(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(alerts.firingNow(clusterId), null);
    }

    @GetMapping("/history")
    public HistoryPage<AlertFiringView> history(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String after) {
        return alerts.history(clusterId, ResourceQuery.ofPage(page, size), Cursor.decode(after));
    }

    @GetMapping("/rules")
    public PagedView<AlertRuleView> rules(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(ruleService.list(clusterId), null);
    }

    @GetMapping("/plugin-metrics")
    public PagedView<PluginMetricView> pluginMetrics(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(ruleService.pluginMetrics(clusterId), null);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleView createRule(@PathVariable UUID clusterId, @Valid @RequestBody AlertRuleRequest request) {
        return ruleService.create(clusterId, request);
    }

    @PutMapping("/rules/{ruleId}")
    public AlertRuleView updateRule(
            @PathVariable UUID clusterId, @PathVariable UUID ruleId, @Valid @RequestBody AlertRuleRequest request) {
        return ruleService.update(clusterId, ruleId, request);
    }

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID clusterId, @PathVariable UUID ruleId) {
        ruleService.delete(clusterId, ruleId);
    }
}
