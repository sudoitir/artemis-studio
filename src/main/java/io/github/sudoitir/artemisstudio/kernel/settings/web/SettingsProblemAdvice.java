package io.github.sudoitir.artemisstudio.kernel.settings.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsInvalidException;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A change set with invalid values is a {@code 400} that names each invalid setting, like any invalid form. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SettingsProblemAdvice {

    @ExceptionHandler(SettingsInvalidException.class)
    ProblemDetail onInvalid(SettingsInvalidException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.BAD_REQUEST, "validation", "Invalid request", "One or more settings are invalid.");
        List<Map<String, String>> errors = e.fieldErrors().entrySet().stream()
                .map(entry -> Map.of("field", entry.getKey(), "message", entry.getValue()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }
}
