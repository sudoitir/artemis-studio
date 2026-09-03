package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.ClusterService;
import io.github.sudoitir.artemisstudio.web.dto.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.artemisstudio.web.dto.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.ClusterSummary;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.TopologyView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 1 cluster API (base {@code /api/v1}). Realtime is SSE only (ADR-0003);
 * every command here is an ordinary POST/PATCH/DELETE. Destructive operations take
 * {@code ?dryRun=true} and typed confirmation is enforced in the UI
 * (non-negotiable #2).
 */
@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterService service;

    /** Register from a list of seed URLs (ADR-0013). {@code ?dryRun=true} probes and returns without persisting. */
    @PostMapping
    public ResponseEntity<Object> register(
            @Valid @RequestBody RegisterClusterRequest request, @RequestParam(defaultValue = "false") boolean dryRun) {
        if (dryRun) {
            return ResponseEntity.ok(unwrap(service.checkConnection(request)));
        }
        ClusterDetail detail = unwrap(service.register(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @GetMapping
    public List<ClusterSummary> list() {
        return service.list();
    }

    @GetMapping("/{clusterId}")
    public ClusterDetail get(@PathVariable UUID clusterId) {
        return service.get(clusterId);
    }

    @DeleteMapping("/{clusterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID clusterId) {
        service.delete(clusterId);
    }

    @GetMapping("/{clusterId}/capabilities")
    public CapabilitiesView capabilities(@PathVariable UUID clusterId) {
        return service.capabilities(clusterId);
    }

    @GetMapping("/{clusterId}/topology")
    public TopologyView topology(@PathVariable UUID clusterId) {
        return service.topology(clusterId);
    }

    @GetMapping("/{clusterId}/health")
    public HealthView health(@PathVariable UUID clusterId) {
        return service.health(clusterId);
    }

    @PostMapping("/{clusterId}/rediscover")
    public TopologyView rediscover(@PathVariable UUID clusterId) {
        return unwrap(service.rediscover(clusterId));
    }

    @PatchMapping("/{clusterId}/nodes/{nodeId}")
    public NodeEndpointView overrideNode(
            @PathVariable UUID clusterId, @PathVariable UUID nodeId, @Valid @RequestBody NodeOverrideRequest request) {
        return unwrap(service.overrideNodeUrl(clusterId, nodeId, request));
    }

    /** A {@link Attempt.Failed} becomes a classified {@link BrokerConnectionException} for the advice to render. */
    private static <T> T unwrap(Attempt<T> attempt) {
        return switch (attempt) {
            case Attempt.Ok<T> ok -> ok.value();
            case Attempt.Failed<T> failed -> throw new BrokerConnectionException(failed.kind(), failed.detail());
        };
    }
}
