package io.github.sudoitir.artemisstudio.feature.triage.web;

import io.github.sudoitir.artemisstudio.feature.triage.ConsumerHealth;
import io.github.sudoitir.artemisstudio.feature.triage.ConsumerHealthService;
import io.github.sudoitir.artemisstudio.feature.triage.web.TriageViews.ConsumerHealthView;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer-health reads (consumer-health spec, ADR-0089). Read-only; no broker call. */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/consumer-health")
@RequiredArgsConstructor
public class TriageController {

    private final ConsumerHealthService consumerHealth;

    /**
     * The cluster's queues with their verdicts, worst first unless {@code sort} says
     * otherwise. {@code queue} narrows to one — the drawer panel's read.
     *
     * <p>Access is enforced inside the service, before it reads anything, so an
     * unauthorised caller cannot tell a rejected query from a cluster they may not see.
     */
    @GetMapping
    public PagedView<ConsumerHealthView> consumerHealth(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String queue,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (queue != null && !queue.isBlank()) {
            ConsumerHealthView one = consumerHealth
                    .forQueue(clusterId, queue.trim())
                    .map(ConsumerHealthView::of)
                    .orElseThrow(() -> new NotFoundException("queue", queue));
            return new PagedView<>(List.of(one), 1, 1, 1);
        }
        PagedView<ConsumerHealth> result = consumerHealth.page(clusterId, ResourceQuery.of(q, page, size, sort));
        return new PagedView<>(
                result.data().stream().map(ConsumerHealthView::of).toList(),
                result.count(),
                result.page(),
                result.pageSize());
    }
}
