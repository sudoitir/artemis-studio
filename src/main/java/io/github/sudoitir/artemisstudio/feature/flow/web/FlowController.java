package io.github.sudoitir.artemisstudio.feature.flow.web;

import io.github.sudoitir.artemisstudio.feature.flow.FlowGraphService;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowGraphView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The flow graph of one cluster (flow-visualization spec). Read-only. Every read also counts the
 * cluster as observed, so a client that polls without the stream still keeps sampling alive.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/flow")
@RequiredArgsConstructor
public class FlowController {

    private final FlowGraphService graphs;

    /**
     * @param focus {@code client:<name>}, {@code address:<name>} or {@code queue:<name>}
     * @param hops how far a focus reaches, 1 to 3
     * @param limit paths to draw, at most 200
     * @param layers comma-separated routing layers; blank for diverts, bridges and cluster
     * @param byNode also break each queue, address, client edge and broker node down per node (ADR-0110)
     */
    @GetMapping
    public ResponseEntity<FlowGraphView> graph(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String focus,
            @RequestParam(defaultValue = "1") int hops,
            @RequestParam(defaultValue = "IN") FlowQuery.Rank rank,
            @RequestParam(defaultValue = "" + FlowQuery.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "CLIENT_ID") FlowQuery.GroupBy groupBy,
            @RequestParam(required = false) String layers,
            @RequestParam(defaultValue = "false") boolean byNode,
            WebRequest request) {
        FlowGraphView view = graphs.graph(
                clusterId,
                FlowQuery.of(focus, hops, rank, limit, groupBy, layers).withByNode(byNode));
        String etag = "\"" + Integer.toHexString(view.hashCode()) + "\"";
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(view);
    }
}
