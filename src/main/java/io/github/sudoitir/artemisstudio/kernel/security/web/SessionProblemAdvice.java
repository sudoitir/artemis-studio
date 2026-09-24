package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.MustChangePasswordException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationFailedException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
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

    @ExceptionHandler(ReauthenticationRequiredException.class)
    ProblemDetail onReauthenticationRequired(ReauthenticationRequiredException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "reauthentication-required", "Confirm it is you", e.getMessage());
    }

    @ExceptionHandler(ReauthenticationFailedException.class)
    ProblemDetail onReauthenticationFailed(ReauthenticationFailedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "reauthentication-failed", "Not confirmed", e.getMessage());
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
