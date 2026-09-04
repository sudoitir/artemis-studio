package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.RequestReplyService;
import io.github.sudoitir.artemisstudio.service.RrMetrics;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.CreateExpectationRequest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.ExpectationView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowPageView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.StatsResponse;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.UpdateExpectationRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * Request-reply tracing (request-reply-tracing spec): expectations, the
 * reconstructed flows, and their latency/coverage stats. Capability gating
 * (no NOTIFICATIONS, or no resolvable Core URL) is surfaced the same way as
 * {@code events} — via the cluster's {@code capabilities.notifications} on
 * {@code GET /clusters/{id}}, not a per-endpoint check here.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/rr")
@RequiredArgsConstructor
public class RequestReplyController {

    private final RequestReplyService requestReply;
    private final RrMetrics metrics;

    @GetMapping("/expectations")
    public List<ExpectationView> listExpectations(@PathVariable UUID clusterId) {
        return requestReply.list(clusterId);
    }

    @PostMapping("/expectations")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpectationView createExpectation(
            @PathVariable UUID clusterId, @Valid @RequestBody CreateExpectationRequest request) {
        return requestReply.create(clusterId, request);
    }

    @PutMapping("/expectations/{expectationId}")
    public ExpectationView updateExpectation(
            @PathVariable UUID clusterId,
            @PathVariable UUID expectationId,
            @Valid @RequestBody UpdateExpectationRequest request) {
        return requestReply.update(clusterId, expectationId, request);
    }

    @DeleteMapping("/expectations/{expectationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpectation(@PathVariable UUID clusterId, @PathVariable UUID expectationId) {
        requestReply.delete(clusterId, expectationId);
    }

    @GetMapping("/flows")
    public FlowPageView flows(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return requestReply.flowPage(clusterId, state, address, correlationId, from, to, page, size);
    }

    @GetMapping("/flows/{flowId}")
    public FlowView flow(@PathVariable UUID clusterId, @PathVariable UUID flowId) {
        return requestReply.flow(clusterId, flowId);
    }

    @GetMapping("/stats")
    public StatsResponse stats(@PathVariable UUID clusterId, @RequestParam(defaultValue = "PT15M") String window) {
        return metrics.stats(clusterId, java.time.Duration.parse(window));
    }
}
