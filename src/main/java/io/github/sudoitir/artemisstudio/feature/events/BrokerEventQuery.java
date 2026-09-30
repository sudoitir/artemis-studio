package io.github.sudoitir.artemisstudio.feature.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The filters and page of a broker-event history read; a blank or null filter matches everything.
 *
 * @param type the notification type
 * @param nodeId the node it came from
 * @param address the address it concerns
 * @param from earliest timestamp, inclusive
 * @param to latest timestamp, inclusive
 * @param page 1-based page number
 * @param size rows per page, at most 500
 */
public record BrokerEventQuery(
        String type, UUID nodeId, String address, Instant from, Instant to, int page, int size) {}
