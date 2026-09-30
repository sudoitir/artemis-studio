package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/** Answers a refused request as a 403 problem: {@code csrf} for a CSRF denial, {@code forbidden} otherwise. */
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;

    ProblemAccessDeniedHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        ProblemAuthenticationEntryPoint.write(
                json,
                response,
                e instanceof CsrfException
                        ? Problems.of(
                                HttpStatus.FORBIDDEN,
                                "csrf",
                                "CSRF token missing or invalid",
                                "Send the X-XSRF-TOKEN header with the XSRF-TOKEN cookie's value.")
                        : Problems.of(
                                HttpStatus.FORBIDDEN,
                                "forbidden",
                                "Access denied",
                                "You do not have permission to do this."));
    }
}
