package io.github.sudoitir.artemisstudio.kernel.audit.web;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditQuery;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditQueryService;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditQueryService.Export;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit-log screen's data source (non-negotiable #3). Every mutating call in
 * the product writes an {@code audit_event}; this reads them back, filtered by
 * user / action / outcome / time, newest first.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AuditQueryService audit;
    private final ObjectMapper mapper;

    @GetMapping
    public PagedView<AuditEventView> list(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ResourceQuery paging = ResourceQuery.ofPage(page, size);
        return audit.page(
                clusterId, new AuditQuery(user, action, outcome, parentId, from, to, paging.page(), paging.size()));
    }

    /**
     * The trail under the same filters as {@link #list}, oldest first, streamed to the client as a file so a long
     * trail never sits in memory. Needs {@code audit:export}, and holds what the caller could read in the list.
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportAudit(
            @PathVariable UUID clusterId,
            @RequestParam String format,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        AuditExportFormat type = AuditExportFormat.parse(format);
        Export export = audit.prepareExport(
                clusterId, new AuditQuery(user, action, outcome, parentId, from, to, 1, 1), type.extension());
        StreamingResponseBody body = out -> {
            try (AuditExportFormat.Sink sink = type.open(out, mapper)) {
                audit.run(export, sink::write, sink::close);
            }
        };
        String fileName = "audit-%s-%s.%s"
                .formatted(
                        export.clusterName().replaceAll("[^A-Za-z0-9._-]", "-"),
                        STAMP.format(Instant.now()),
                        type.extension());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName)
                                .build()
                                .toString())
                .body(body);
    }

    /** One audit event by id, for a shared link. */
    @GetMapping("/{id}")
    public AuditEventView get(@PathVariable UUID clusterId, @PathVariable long id) {
        return audit.get(clusterId, id);
    }
}
