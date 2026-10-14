package io.github.sudoitir.rahdar.kernel.core;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A page of a history that can be very long (ADR-0196): audit events, broker events, alert firings. It
 * pages by page number for the first {@value #MAX_OFFSET} rows, with a total, and by {@code next} cursor
 * beyond that, or whenever the caller prefers, without one.
 *
 * @param count the total; null when the page was reached by cursor, which does not count
 * @param next the cursor of the page after this one; null on the last page
 * @param mayLag the page was read from the read replica, which may be a little behind the primary
 */
public record HistoryPage<T>(
        @Schema(requiredMode = REQUIRED) List<T> data,
        @Schema(requiredMode = REQUIRED) int page,
        @Schema(requiredMode = REQUIRED) int pageSize,

        @Schema(nullable = true, description = "The total; null when the page was reached by cursor.")
        Long count,

        @Schema(requiredMode = REQUIRED) boolean hasNext,

        @Schema(nullable = true, description = "Pass as `after` for the page that follows; null on the last page.")
        String next,

        @Schema(requiredMode = REQUIRED, description = "Read from a replica that may be a little behind.")
        boolean mayLag) {

    /** How deep a page number may reach; past it a caller pages by cursor. */
    public static final int MAX_OFFSET = 10_000;

    /** Refuses a page number past {@link #MAX_OFFSET}, naming the cursor that reaches further. */
    public static void requireReachable(ResourceQuery paging) {
        if ((long) paging.page() * paging.size() > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "Page numbers reach the first %d rows. Narrow the time range, or pass `after` with the `next` of the previous page to read on."
                            .formatted(MAX_OFFSET));
        }
    }
}
