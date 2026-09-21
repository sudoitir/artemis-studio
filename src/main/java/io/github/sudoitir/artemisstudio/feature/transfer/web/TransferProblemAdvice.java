package io.github.sudoitir.artemisstudio.feature.transfer.web;

import io.github.sudoitir.artemisstudio.feature.transfer.TransferRefusedException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A transfer refused before anything was done (ADR-0097): nothing selected, the preview refused it
 * or expired, a warning not acknowledged, a move not confirmed, or a run with nothing to return.
 */
@RestControllerAdvice
class TransferProblemAdvice {

    @ExceptionHandler(TransferRefusedException.class)
    ProblemDetail onTransferRefused(TransferRefusedException e) {
        return Problems.of(e.status(), e.slug(), "Transfer refused", e.getMessage());
    }
}
