package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.approval.TooManyHeldOperationsException;
import io.github.sudoitir.artemisstudio.kernel.approval.VoteRefusedException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalReasonRequiredException;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.artemisstudio.kernel.gate.GateContext;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationDeniedException;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationHeldException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * How the approval gate answers over HTTP, whichever endpoint reached it (ADR-0179): held is {@code 202} naming the
 * held operation, denied {@code 403 operation-denied}, an unavailable provider {@code 503 approval-unavailable}, a
 * missing reason {@code 422 approval-reason-required}, and too many open requests {@code 429}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GateProblemAdvice {

    @ExceptionHandler(OperationHeldException.class)
    ResponseEntity<HeldOperationViews.HeldOutcomeView> onHeld(OperationHeldException e) {
        return ResponseEntity.accepted()
                .header(GateContext.HELD_HEADER, e.heldId().toString())
                .location(URI.create(e.link()))
                .body(new HeldOperationViews.HeldOutcomeView(
                        "held", new HeldOperationViews.HeldRefView(e.heldId(), e.summary(), e.expiresAt(), e.link())));
    }

    @ExceptionHandler(OperationDeniedException.class)
    ProblemDetail onDenied(OperationDeniedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "operation-denied", "Not allowed", e.getMessage());
    }

    @ExceptionHandler(ApprovalUnavailableException.class)
    ProblemDetail onUnavailable(ApprovalUnavailableException e) {
        return Problems.of(
                HttpStatus.SERVICE_UNAVAILABLE, "approval-unavailable", "Approval unavailable", e.getMessage());
    }

    @ExceptionHandler(ApprovalReasonRequiredException.class)
    ProblemDetail onReasonRequired(ApprovalReasonRequiredException e) {
        return Problems.of(
                HttpStatus.UNPROCESSABLE_ENTITY, "approval-reason-required", "A reason is needed", e.getMessage());
    }

    @ExceptionHandler(TooManyHeldOperationsException.class)
    ProblemDetail onTooMany(TooManyHeldOperationsException e) {
        return Problems.of(
                HttpStatus.TOO_MANY_REQUESTS, "too-many-held-operations", "Too many open requests", e.getMessage());
    }

    @ExceptionHandler(VoteRefusedException.class)
    ProblemDetail onVoteRefused(VoteRefusedException e) {
        return Problems.of(e.status(), e.slug(), "Decision refused", e.getMessage());
    }
}
