package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.artemisstudio.service.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.service.ConflictException;
import io.github.sudoitir.artemisstudio.service.LoginThrottledException;
import io.github.sudoitir.artemisstudio.service.MustChangePasswordException;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the API's failure modes into RFC 9457 {@link ProblemDetail}s with a
 * stable {@code type} URI, so the frontend switches on the class of failure
 * rather than string-matching a message.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final String TYPE_BASE = "https://artemis-studio.dev/problems/";

    @ExceptionHandler(BrokerConnectionException.class)
    ProblemDetail onBrokerConnection(BrokerConnectionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusFor(e.kind()), e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "broker-" + kebab(e.kind())));
        problem.setTitle(titleFor(e.kind()));
        problem.setProperty("brokerErrorKind", e.kind().name());
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "not-found"));
        problem.setTitle("Resource not found");
        return problem;
    }

    @ExceptionHandler(BulkCapExceededException.class)
    ProblemDetail onBulkCap(BulkCapExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "bulk-cap-exceeded"));
        problem.setTitle("Safety cap exceeded");
        problem.setProperty("affectedCount", e.affectedCount());
        problem.setProperty("cap", e.cap());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail onIllegalState(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "notification-delivery-failed"));
        problem.setTitle("Notification delivery failed");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "invalid-value"));
        problem.setTitle("Invalid value");
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail onConflict(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + e.slug()));
        problem.setTitle("Conflict");
        return problem;
    }

    @ExceptionHandler(MustChangePasswordException.class)
    ProblemDetail onMustChangePassword(MustChangePasswordException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "must-change-password"));
        problem.setTitle("Password change required");
        return problem;
    }

    @ExceptionHandler(LoginThrottledException.class)
    ProblemDetail onLoginThrottled(LoginThrottledException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "login-throttled"));
        problem.setTitle("Too many attempts");
        return problem;
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ProblemDetail onBadCredentials(Exception e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        problem.setType(URI.create(TYPE_BASE + "invalid-credentials"));
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setType(URI.create(TYPE_BASE + "validation"));
        problem.setTitle("Invalid request");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    private static HttpStatus statusFor(Kind kind) {
        return switch (kind) {
            case UNAUTHORIZED, NOT_ARTEMIS, WRONG_PATH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNREACHABLE, TLS_FAILED, BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static String titleFor(Kind kind) {
        return switch (kind) {
            case UNREACHABLE -> "Broker unreachable";
            case UNAUTHORIZED -> "Broker rejected the credentials";
            case NOT_ARTEMIS -> "No Artemis broker at this agent";
            case WRONG_PATH -> "No Jolokia agent at this address";
            case TLS_FAILED -> "TLS handshake failed";
            case BAD_RESPONSE -> "Unexpected broker response";
        };
    }

    private static String kebab(Kind kind) {
        return kind.name().toLowerCase().replace('_', '-');
    }
}
