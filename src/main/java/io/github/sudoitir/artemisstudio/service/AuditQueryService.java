package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.web.dto.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.web.dto.AuditViews.AuditPageView;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the audit trail — filtered, paged, newest first (non-negotiable #3). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository events;

    @Transactional(readOnly = true)
    public AuditPageView page(
            UUID clusterId,
            String username,
            String action,
            String outcome,
            Instant from,
            Instant to,
            int page,
            int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        Page<AuditEventEntity> result = events.findPage(
                clusterId,
                blankToNull(username),
                blankToNull(action),
                blankToNull(outcome),
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(p - 1, s));
        return new AuditPageView(
                result.getContent().stream().map(AuditQueryService::toView).toList(), result.getTotalElements(), p, s);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static AuditEventView toView(AuditEventEntity e) {
        return new AuditEventView(
                e.getTs(),
                e.getUsername(),
                e.getSourceIp(),
                e.getRequestId(),
                e.getAction(),
                e.getTargetType(),
                e.getTargetName(),
                e.getAffectedCount(),
                e.getOutcome(),
                e.isDryRun(),
                e.getParams(),
                e.getError(),
                e.getNodeId());
    }
}
