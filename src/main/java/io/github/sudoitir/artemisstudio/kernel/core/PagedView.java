package io.github.sudoitir.artemisstudio.kernel.core;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of any listing (ADR-0149). {@code count} is the total across all nodes, not the page size, and is
 * null only where the total is unknown; {@code hasNext} says whether another page follows either way.
 */
public record PagedView<T>(
        @Schema(requiredMode = REQUIRED) List<T> data,
        @Schema(requiredMode = REQUIRED) int page,
        @Schema(requiredMode = REQUIRED) int pageSize,

        @Schema(nullable = true, description = "The total across all pages; null when it is not known.")
        Long count,

        @Schema(requiredMode = REQUIRED) boolean hasNext) {

    /** A page of a table read with a Spring Data {@code Page} (1-based {@code page} on the wire). */
    public static <S, T> PagedView<T> of(Page<S> result, Function<S, T> map) {
        return new PagedView<>(
                result.getContent().stream().map(map).toList(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.hasNext());
    }

    public <R> PagedView<R> map(Function<T, R> f) {
        return new PagedView<>(data.stream().map(f).toList(), page, pageSize, count, hasNext);
    }
}
