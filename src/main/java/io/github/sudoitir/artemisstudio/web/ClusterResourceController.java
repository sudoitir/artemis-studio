package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.CrossNodeAggregator;
import io.github.sudoitir.artemisstudio.service.PagedListService;
import io.github.sudoitir.artemisstudio.service.ResourceQuery;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.AddressView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConnectionView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConsumerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ProducerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.QueueView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.SessionView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cross-node resource views (ADR-0017). {@code queues} is aggregated from the
 * {@code queue_snapshot} cache; the rest are live-through fan-outs. All share the
 * {@code ?q=&page=&size=&sort=} envelope. A cluster with no reachable management
 * endpoint surfaces the classified {@code BrokerConnectionException} through
 * {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class ClusterResourceController {

    private final CrossNodeAggregator aggregator;
    private final PagedListService pagedList;

    @GetMapping("/queues")
    public PagedView<QueueView> queues(@PathVariable UUID clusterId, ResourceQuery query) {
        return aggregator.queues(clusterId, query);
    }

    @GetMapping("/addresses")
    public PagedView<AddressView> addresses(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.addresses(clusterId, query);
    }

    @GetMapping("/consumers")
    public PagedView<ConsumerView> consumers(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.consumers(clusterId, query);
    }

    @GetMapping("/sessions")
    public PagedView<SessionView> sessions(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.sessions(clusterId, query);
    }

    @GetMapping("/connections")
    public PagedView<ConnectionView> connections(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.connections(clusterId, query);
    }

    @GetMapping("/producers")
    public PagedView<ProducerView> producers(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.producers(clusterId, query);
    }

    /** Binds {@code ?q=&page=&size=&sort=} to the shared query envelope. */
    @org.springframework.web.bind.annotation.ModelAttribute
    ResourceQuery resourceQuery(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ResourceQuery.of(q, page, size, sort);
    }
}
