package io.github.sudoitir.artemisstudio.feature.rr;

import java.time.Instant;

/**
 * The filters and page of a request-reply flow list; a blank or null filter matches everything.
 *
 * @param state the flow state
 * @param address the request address
 * @param correlationId the correlation id
 * @param from earliest timestamp, inclusive
 * @param to latest timestamp, inclusive
 * @param page 1-based page number
 * @param size rows per page, at most 500
 */
public record FlowQuery(
        String state, String address, String correlationId, Instant from, Instant to, int page, int size) {}
