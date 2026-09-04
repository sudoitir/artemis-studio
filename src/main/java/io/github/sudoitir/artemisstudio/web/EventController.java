package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.BrokerEventService;
import io.github.sudoitir.artemisstudio.web.dto.EventViews.BrokerEventPageView;
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
 * The events screen's data source (ADR-0026, ADR-0028): the cluster's
 * {@code activemq.notifications} history, filtered by type / node / address /
 * time, newest first. The envelope carries the dropped-event count and the
 * oldest retained event so buffer overflow is visible.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/events")
@RequiredArgsConstructor
public class EventController {

    private final BrokerEventService events;

    @GetMapping
    public BrokerEventPageView list(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID nodeId,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return events.page(clusterId, type, nodeId, address, from, to, page, size);
    }
}
