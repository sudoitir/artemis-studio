package io.github.sudoitir.artemisstudio.kernel.core.web;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The failure modes every module shares, as problem details ({@link Problems}).
 * A failure that belongs to one module — a broker refusal, a SQL dialect error, a
 * login throttle — is mapped by that module's own advice.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "invalid-value", "Invalid value", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail onConflict(ConflictException e) {
        return Problems.of(HttpStatus.CONFLICT, e.slug(), "Conflict", e.getMessage());
    }

    /**
     * A last line of defence, not the intended path. Services check for a conflict
     * up front and throw {@link ConflictException}; this keeps any constraint that
     * slips through from reaching the client as a 500 with no usable body.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Unmapped constraint violation surfaced to the API", e);
        return Problems.of(
                HttpStatus.CONFLICT,
                "constraint-violation",
                "Conflict",
                "This conflicts with something that already exists.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem =
                Problems.of(HttpStatus.BAD_REQUEST, "validation", "Invalid request", "One or more fields are invalid.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }
}
