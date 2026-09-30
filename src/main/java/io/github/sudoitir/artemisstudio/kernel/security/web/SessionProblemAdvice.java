package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.MfaEnrolmentRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.MustChangePasswordException;
import io.github.sudoitir.artemisstudio.kernel.security.PasswordPolicyException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationFailedException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorInvalidException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SignInExpiredException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sign-in failures as problem details. First in line, so a sign-in failure keeps its own type rather than
 * the general {@code unauthenticated} that {@code ApiExceptionHandler} gives any authentication failure.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class SessionProblemAdvice {

    @ExceptionHandler(MustChangePasswordException.class)
    ProblemDetail onMustChangePassword(MustChangePasswordException e) {
        return Problems.of(HttpStatus.LOCKED, "must-change-password", "Password change required", e.getMessage());
    }

    @ExceptionHandler(MfaEnrolmentRequiredException.class)
    ProblemDetail onMfaEnrolmentRequired(MfaEnrolmentRequiredException e) {
        return Problems.of(
                HttpStatus.LOCKED, "mfa-enrolment-required", "Two-step verification required", e.getMessage());
    }

    @ExceptionHandler(SessionRequiredException.class)
    ProblemDetail onSessionRequired(SessionRequiredException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "session-required", "Sign in to do this", e.getMessage());
    }

    @ExceptionHandler(SecondFactorRequiredException.class)
    ProblemDetail onSecondFactorRequired(SecondFactorRequiredException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "mfa-required", "Two-step verification required", e.getMessage());
    }

    @ExceptionHandler(SecondFactorInvalidException.class)
    ProblemDetail onSecondFactorInvalid(SecondFactorInvalidException e) {
        return Problems.of(HttpStatus.UNAUTHORIZED, "second-factor-invalid", "Code not accepted", e.getMessage());
    }

    @ExceptionHandler(SignInExpiredException.class)
    ProblemDetail onSignInExpired(SignInExpiredException e) {
        return Problems.of(HttpStatus.UNAUTHORIZED, "sign-in-expired", "Sign-in timed out", e.getMessage());
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ProblemDetail> onLoginThrottled(LoginThrottledException e) {
        // ponytail: fixed hint, the limiter's real unlock time is not exposed; surface it if clients need precision
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(Problems.of(
                        HttpStatus.TOO_MANY_REQUESTS, "login-throttled", "Too many attempts", e.getMessage()));
    }

    @ExceptionHandler(PasswordPolicyException.class)
    ProblemDetail onPasswordPolicy(PasswordPolicyException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "password-policy", "Password not accepted", e.getMessage());
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
