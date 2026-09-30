package io.github.sudoitir.artemisstudio.kernel.core.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.core.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The last resolver in the chain: a failure no advice mapped becomes a 500 {@code internal-error}
 * problem that carries a request id and never the cause. It runs after every {@code @ExceptionHandler},
 * so a module advice is never shadowed, which a catch-all advice could not guarantee.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UnexpectedFailureResolver implements HandlerExceptionResolver, WebMvcConfigurer {

    private final JsonMapper json;

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception e) {
        String requestId = RequestIds.of(request);
        log.error("Unhandled failure in request {}", requestId, e);
        ProblemDetail problem = Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "Something went wrong. Quote the request id when reporting it.");
        problem.setProperty("requestId", requestId);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        try {
            json.writeValue(response.getOutputStream(), problem);
        } catch (IOException ioe) {
            log.debug("Could not write the internal-error problem", ioe);
        }
        return new ModelAndView();
    }
}
