package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.sql.MessageVerifier;
import io.github.sudoitir.artemisstudio.sql.QueryPlan;
import io.github.sudoitir.artemisstudio.sql.SqlConsoleService;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.PlanView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.SqlQueryRequest;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.VerifyRequest;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.VerifyView;
import io.github.sudoitir.artemisstudio.web.mapper.SqlViewMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SQL Console's read surface (ADR-0058). {@code SELECT} only — the dialect can
 * express no mutation, and acting on a result row goes back through
 * {@link MessageController}, which already carries the dry run, the bulk cap and the
 * audit record.
 *
 * <p>{@code /plan} contacts nothing and is safe to call while the operator types.
 * {@code /query} is the one that costs a broker something, so it is the one the cost
 * ceiling refuses and the audit log records.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/sql")
@RequiredArgsConstructor
public class SqlController {

    private final SqlConsoleService console;
    private final MessageVerifier verifier;
    private final SqlViewMapper mapper;

    /** Parse, validate and cost a query without running it. */
    @PostMapping("/plan")
    public PlanView plan(@PathVariable UUID clusterId, @RequestBody SqlQueryRequest request) {
        QueryPlan plan = console.plan(clusterId, request.sql());
        return mapper.toView(plan);
    }

    /**
     * Ask the broker whether one indexed message is still on its queue. The index can
     * only say what it saw; this is the operation that asks the authority, and it
     * answers UNKNOWN rather than GONE whenever the read could not settle it.
     */
    @PostMapping("/verify")
    public VerifyView verify(@PathVariable UUID clusterId, @RequestBody VerifyRequest request) {
        MessageVerifier.Verdict verdict = verifier.verify(
                clusterId, request.nodeId(), request.queueName(), request.messageId(), request.timestamp());
        return new VerifyView(verdict.presence().name(), verdict.detail());
    }
}
