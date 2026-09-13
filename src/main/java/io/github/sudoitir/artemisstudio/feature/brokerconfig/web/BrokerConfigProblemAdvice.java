package io.github.sudoitir.artemisstudio.feature.brokerconfig.web;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigInvalidException;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.HazardNotAcknowledgedException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Declared-configuration refusals as problem details. */
@RestControllerAdvice
class BrokerConfigProblemAdvice {

    /**
     * A declaration that cannot be saved or applied (ADR-0067 D10). Same shape as
     * bean validation — {@code errors} with a field path per problem — so the form
     * can focus the first invalid field.
     */
    @ExceptionHandler(BrokerConfigInvalidException.class)
    ProblemDetail onConfigInvalid(BrokerConfigInvalidException e) {
        ProblemDetail problem =
                Problems.of(HttpStatus.BAD_REQUEST, "config-invalid", "The declaration is invalid", e.getMessage());
        problem.setProperty(
                "errors",
                e.violations().stream()
                        .map(v -> Map.of("field", v.path(), "message", v.message()))
                        .toList());
        return problem;
    }

    /** A real run without every High hazard acknowledged (ADR-0067 D7). The ids travel so the client can name them. */
    @ExceptionHandler(HazardNotAcknowledgedException.class)
    ProblemDetail onHazard(HazardNotAcknowledgedException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.UNPROCESSABLE_ENTITY, "hazard-not-acknowledged", "Hazards not acknowledged", e.getMessage());
        problem.setProperty("missing", e.missing());
        return problem;
    }
}
