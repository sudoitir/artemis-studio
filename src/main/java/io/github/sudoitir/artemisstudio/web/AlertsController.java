package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.AlertRuleService;
import io.github.sudoitir.artemisstudio.service.AlertService;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertFiringPageView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertFiringView;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleView;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1/clusters/{clusterId}/alerts")
@RequiredArgsConstructor
public class AlertsController {

    private final AlertService alerts;
    private final AlertRuleService ruleService;

    @GetMapping("/firing")
    public List<AlertFiringView> firing(@PathVariable UUID clusterId) {
        return alerts.firingNow(clusterId);
    }

    @GetMapping("/history")
    public AlertFiringPageView history(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return alerts.history(clusterId, page, size);
    }

    @GetMapping("/rules")
    public List<AlertRuleView> rules(@PathVariable UUID clusterId) {
        return ruleService.list(clusterId);
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
