package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.service.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.service.ConflictException;
import io.github.sudoitir.artemisstudio.service.LoginThrottledException;
import io.github.sudoitir.artemisstudio.service.MustChangePasswordException;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import io.github.sudoitir.artemisstudio.sql.CostRefusedException;
import io.github.sudoitir.artemisstudio.sql.SqlConsoleService;
import io.github.sudoitir.artemisstudio.sql.SqlSyntaxException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
@Slf4j
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

    /**
     * A management refusal that reached the controller rather than being folded
     * into a per-node outcome — an address delete blocked by bound queues (D8), or
     * an argument the broker will not accept. 409 rather than 400: the request is
     * well-formed, the resource's current state is what refuses it.
     */
    @ExceptionHandler(ManagementRefusal.class)
    ProblemDetail onManagementRefusal(ManagementRefusal e) {
        HttpStatus status = e.kind() == ManagementRefusal.Kind.ARGUMENT ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "management-refused"));
        problem.setTitle("The broker refused this operation");
        problem.setProperty("refusalKind", e.kind().name());
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

    /**
     * A query that is not in the dialect (ADR-0058 D2). The offending token and the
     * near match travel as properties so the editor can put the caret on the word.
     */
    @ExceptionHandler(SqlSyntaxException.class)
    ProblemDetail onSqlSyntax(SqlSyntaxException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "sql-syntax"));
        problem.setTitle("That is not the console's dialect");
        if (e.offending() != null) {
            problem.setProperty("offending", e.offending());
        }
        if (e.suggestion() != null) {
            problem.setProperty("suggestion", e.suggestion());
        }
        return problem;
    }

    /**
     * A query refused before its first broker call (ADR-0058 D6). It carries the
     * estimate, the ceiling and the way to narrow it, because "too expensive" with no
     * number is not something an operator can act on.
     */
    @ExceptionHandler(CostRefusedException.class)
    ProblemDetail onCostRefused(CostRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "query-too-expensive"));
        problem.setTitle("Query refused before it was started");
        problem.setProperty("estimate", e.estimate());
        problem.setProperty("ceiling", e.ceiling());
        problem.setProperty("hint", e.hint());
        return problem;
    }

    @ExceptionHandler(SqlConsoleService.TooManyQueriesException.class)
    ProblemDetail onTooManyQueries(SqlConsoleService.TooManyQueriesException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "too-many-queries"));
        problem.setTitle("Too many queries at once");
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

    /**
     * A last line of defence, not the intended path. Services check for a conflict
     * up front and throw {@link ConflictException}; this keeps any constraint that
     * slips through from reaching the client as a 500 with no usable body.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Unmapped constraint violation surfaced to the API", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This conflicts with something that already exists.");
        problem.setType(URI.create(TYPE_BASE + "constraint-violation"));
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
