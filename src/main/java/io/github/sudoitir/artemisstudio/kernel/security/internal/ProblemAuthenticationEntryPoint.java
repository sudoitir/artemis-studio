package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/** Answers a request with no valid credentials as a 401 {@code unauthenticated} problem (api-contract spec). */
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper json;

    ProblemAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(
                json,
                response,
                Problems.of(
                        HttpStatus.UNAUTHORIZED,
                        "unauthenticated",
                        "Authentication required",
                        "Sign in, or send a valid bearer token."));
    }

    static void write(JsonMapper json, HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
