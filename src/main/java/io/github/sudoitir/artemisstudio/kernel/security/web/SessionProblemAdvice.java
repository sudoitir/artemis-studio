package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.MustChangePasswordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Sign-in failures as problem details. */
@RestControllerAdvice
class SessionProblemAdvice {

    @ExceptionHandler(MustChangePasswordException.class)
    ProblemDetail onMustChangePassword(MustChangePasswordException e) {
        return Problems.of(HttpStatus.LOCKED, "must-change-password", "Password change required", e.getMessage());
    }

    @ExceptionHandler(LoginThrottledException.class)
    ProblemDetail onLoginThrottled(LoginThrottledException e) {
        return Problems.of(HttpStatus.TOO_MANY_REQUESTS, "login-throttled", "Too many attempts", e.getMessage());
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ProblemDetail onBadCredentials(Exception e) {
        return Problems.of(
                HttpStatus.UNAUTHORIZED,
                "invalid-credentials",
                "Authentication failed",
                "Invalid username or password.");
    }
}
