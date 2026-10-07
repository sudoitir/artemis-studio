package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.approval.Approvals;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Held operations for the signed-in user (ADR-0180): their requests, those they may decide, one in full, a decision
 * and a cancellation. A request the caller may not see is not found. Deciding needs a browser session with a fresh
 * sign-in; an API token or an assistant is refused, and a stale sign-in answers {@code reauthentication-required} so
 * the console confirms it is them.
 */
@RestController
@RequestMapping("/held-operations")
@RequiredArgsConstructor
public class HeldOperationController {

    private static final int MAX_LIMIT = 100;

    private final Approvals approvals;

    @GetMapping
    public HeldOperationViews.HeldOperationPageView list(
            @RequestParam(defaultValue = "MINE") Approvals.Scope scope,
            @RequestParam(required = false) Set<HeldState> state,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return HeldOperationViews.HeldOperationPageView.of(
                approvals.list(scope, state == null ? Set.of() : state, before, limit));
    }

    @GetMapping("/{id}")
    public HeldOperationViews.HeldOperationDetailView get(@PathVariable UUID id) {
        return HeldOperationViews.HeldOperationDetailView.of(approvals.get(id));
    }

    @PostMapping("/{id}/decision")
    public HeldOperationViews.HeldOperationDetailView decide(
            @PathVariable UUID id, @RequestBody HeldOperationViews.HeldDecisionRequest request) {
        if (request.vote() == null || request.paramsHash() == null || request.version() == null) {
            throw new IllegalArgumentException("A decision needs vote, paramsHash and version.");
        }
        return HeldOperationViews.HeldOperationDetailView.of(
                approvals.decide(id, request.vote(), request.reason(), request.paramsHash(), request.version()));
    }

    @PostMapping("/{id}/cancel")
    public HeldOperationViews.HeldOperationDetailView cancel(@PathVariable UUID id) {
        return HeldOperationViews.HeldOperationDetailView.of(approvals.cancel(id));
    }
}
