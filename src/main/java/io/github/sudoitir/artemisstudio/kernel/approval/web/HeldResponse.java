package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that runs a gated operation (ADR-0179): with an approval provider armed it may answer {@code 202}
 * with {@code X-Studio-Held-Operation} and a {@link HeldOperationViews.HeldOutcomeView} instead of its usual result,
 * and the operation has not run. An endpoint that already answers {@code 202} declares both shapes itself.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(
        responseCode = "202",
        description = "Held for approval: the operation has not run. X-Studio-Held-Operation names the request.",
        content = @Content(schema = @Schema(implementation = HeldOperationViews.HeldOutcomeView.class)))
public @interface HeldResponse {}
