package io.github.sudoitir.artemisstudio.feature.bulk.web;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkRefusedException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A bulk run refused before anything was done (ADR-0093): no selection, over the queue cap, or expired. */
@RestControllerAdvice
class BulkProblemAdvice {

    @ExceptionHandler(BulkRefusedException.class)
    ProblemDetail onBulkRefused(BulkRefusedException e) {
        return Problems.of(e.status(), e.slug(), "Bulk run refused", e.getMessage());
    }
}
