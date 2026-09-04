package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.AuditQueryService;
import io.github.sudoitir.artemisstudio.web.dto.AuditViews.AuditPageView;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit-log screen's data source (non-negotiable #3). Every mutating call in
 * the product writes an {@code audit_event}; this reads them back, filtered by
 * user / action / outcome / time, newest first.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService audit;

    @GetMapping
    public AuditPageView list(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.page(clusterId, user, action, outcome, from, to, page, size);
    }
}
