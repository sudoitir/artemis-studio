package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDisabledException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps {@link FeatureDisabledException} to its {@code 404 feature-disabled} problem. */
@RestControllerAdvice
public class FeatureDisabledAdvice {

    @ExceptionHandler(FeatureDisabledException.class)
    public ProblemDetail onFeatureDisabled(FeatureDisabledException e) {
        return e.toProblem();
    }
}
