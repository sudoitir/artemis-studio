package io.github.sudoitir.artemisstudio.feature.plugins.web;

import io.github.sudoitir.artemisstudio.feature.plugins.PluginAccessDeniedException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Plugin refusals as problem details. A refusal lists every reason in {@code violations} — each
 * with what the plugin's author (or the operator) must change — so the review screen can show
 * them all at once instead of one per attempt.
 */
@RestControllerAdvice(assignableTypes = PluginAdminController.class)
class PluginAdminProblemAdvice {

    private static final Set<String> CONFLICTS =
            Set.of("lifecycle-busy", "already-active", "key-exists", "acknowledgement-required");

    private static HttpStatus statusOf(String firstViolationCode) {
        if ("not-found".equals(firstViolationCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("license-empty".equals(firstViolationCode)) {
            return HttpStatus.BAD_REQUEST;
        }
        if ("license-too-large".equals(firstViolationCode)) {
            return HttpStatus.CONTENT_TOO_LARGE;
        }
        return CONFLICTS.contains(firstViolationCode) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT;
    }

    @ExceptionHandler(PluginRefusedException.class)
    ProblemDetail onRefused(PluginRefusedException e) {
        String first = e.violations().isEmpty() ? "" : e.violations().getFirst().code();
        HttpStatus status = statusOf(first);
        ProblemDetail problem = Problems.of(status, "plugin-refused", "Refused", e.getMessage());
        problem.setProperty(
                "violations",
                e.violations().stream().map(PluginAdminController::violation).toList());
        return problem;
    }

    @ExceptionHandler(PluginAccessDeniedException.class)
    ProblemDetail onDenied(PluginAccessDeniedException e) {
        HttpStatus status =
                "plugin-upload-rate-limited".equals(e.slug()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
        return Problems.of(status, e.slug(), "Not allowed", e.getMessage());
    }

    @ExceptionHandler(PluginUploadTooLargeException.class)
    ProblemDetail onTooLarge(PluginUploadTooLargeException e) {
        return Problems.of(HttpStatus.CONTENT_TOO_LARGE, "plugin-too-large", "Too large", e.getMessage());
    }
}
