package io.github.sudoitir.artemisstudio.kernel.core;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of any listing. {@code count} is the total across all nodes, not the page size. */
public record PagedView<T>(
        @Schema(requiredMode = REQUIRED) List<T> data,
        @Schema(requiredMode = REQUIRED) long count,
        @Schema(requiredMode = REQUIRED) int page,
        @Schema(requiredMode = REQUIRED) int pageSize) {}
