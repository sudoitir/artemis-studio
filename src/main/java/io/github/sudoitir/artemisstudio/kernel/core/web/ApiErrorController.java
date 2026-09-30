package io.github.sudoitir.artemisstudio.kernel.core.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the servlet container sends a failure that escaped MVC (a filter error, a bad URL). It replaces
 * Boot's default JSON so those failures are problems too; the cause is never echoed.
 */
@Hidden
@RestController
class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer i && HttpStatus.resolve(i) != null
                ? HttpStatus.valueOf(i)
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String slug = status == HttpStatus.NOT_FOUND
                ? "not-found"
                : status.is4xxClientError() ? "bad-request" : "internal-error";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.of(status, slug, status.getReasonPhrase(), "The request could not be completed."));
    }
}
