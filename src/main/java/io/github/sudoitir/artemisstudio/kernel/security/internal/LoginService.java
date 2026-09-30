package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.AccountLockout;
import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.LoginAttemptLimiter;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationFailedException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorInvalidException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.SignInExpiredException;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TrustedDeviceCookie;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one username-and-password login path (identity-and-sessions spec). It audits the attempt,
 * throttles, asks the named credential provider — {@code local} when none is named — and
 * starts the session. A provider that is not configured fails exactly like a wrong password,
 * so a login request cannot probe which providers exist.
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    public static final String DEFAULT_PROVIDER = "local";

    private static final String INVALID_CREDENTIALS = "invalid credentials";

    private final List<IdentityProviders> contributions;
    private final LoginAttemptLimiter limiter;
    private final SessionAuthentication sessions;
    private final AuthenticationAudit audit;
    private final UserAccounts accounts;
    private final AccountLockout lockout;
    private final GrantLoader grants;
    /** Absent when the module holding the factors is off, which leaves sign-in password only. */
    private final Optional<SecondFactors> secondFactors;

    /** How far a password got a caller: signed in, or waiting for the second factor that completes it. */
    public sealed interface Outcome {
        record Authenticated(StudioPrincipal principal) implements Outcome {}

        /** {@code trustDeviceDays} is how long a browser may be trusted after the factor, rounded up to a day; 0 when it may not. */
        record SecondFactorRequired(List<SessionFacts.Method> methods, int trustDeviceDays) implements Outcome {}
    }

    @Transactional
    public Outcome login(
            String providerId,
            String username,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.clearForLogin(request, response);
        AuthenticationAudit.Attempt attempt = audit.loginAttempted(username, request);
        if (limiter.isLocked(username, request.getRemoteAddr())) {
            attempt.failed("throttled");
            throw new LoginThrottledException();
        }
        String provider = providerIdOrDefault(providerId);
        Optional<StudioPrincipal> principal;
        try {
            principal = credentialProvider(provider).flatMap(p -> p.authenticate(username, password));
        } catch (DisabledException e) {
            lockout.failed(null, username, request);
            attempt.failed(INVALID_CREDENTIALS);
            throw e;
        }
        if (principal.isEmpty()) {
            lockout.failed(lockableAccount(provider, username), username, request);
            attempt.failed(INVALID_CREDENTIALS);
            throw new BadCredentialsException("Invalid username or password");
        }
        StudioPrincipal found = principal.get();
        boolean enrolled = secondFactors.isPresent() && secondFactors.get().enrolled(found.userId());
        boolean trusted = enrolled && trustedDevice(found, request, response);
        if (lockout.isLocked(found.userId()) && !trusted) {
            // Answered exactly like a wrong password, so a locked account is not told apart. A trusted
            // device is the one exception: it stops spraying from locking its owner out of the account.
            lockout.failed(found.userId(), username, request);
            attempt.failed("account locked");
            throw new BadCredentialsException("Invalid username or password");
        }
        if (trusted) {
            // The password and a device the user trusted: signed in, but not fresh, so a step-up still asks for the
            // factor.
            sessions.establish(found, SessionFacts.signedInOnTrustedDevice(request), request, response);
            completed(found, request);
            audit.secondFactorVerified(found.getUsername(), SessionFacts.Method.TRUSTED_DEVICE);
            attempt.succeeded();
            return new Outcome.Authenticated(found);
        }
        if (enrolled) {
            // The password alone opens nothing: no principal, and no success recorded, until the factor.
            sessions.awaitSecondFactor(found.userId(), provider, attempt.awaitingSecondFactor(), request, response);
            return new Outcome.SecondFactorRequired(
                    secondFactors.get().methods(found.userId()), trustDeviceDays(secondFactors.get()));
        }
        StudioPrincipal user =
                secondFactors.filter(f -> f.enrolmentRequired(found.userId())).isPresent()
                        ? found.withSecondFactorEnrolmentRequired(true)
                        : found;
        sessions.establish(user, SessionFacts.signedIn(request), request, response);
        completed(user, request);
        attempt.succeeded();
        return new Outcome.Authenticated(user);
    }

    /**
     * What a caller sent as their second factor: exactly one of a code from an authenticator app, a
     * recovery code, or a browser's answer to a passkey challenge ({@code webauthn}, as JSON), and whether
     * to trust this browser afterwards (a sign-in only).
     */
    public record Submission(String totpCode, String recoveryCode, String webauthn, boolean trustDevice) {}

    /**
     * The options a browser needs to ask for a passkey, as JSON, for whoever is waiting to give a second
     * factor: the sign-in whose password was right, or the step-up whose password was. The challenge is
     * kept in the session and answers one attempt.
     */
    public String passkeyOptions(HttpServletRequest request) {
        SecondFactors factors = secondFactors.orElseThrow(SignInExpiredException::new);
        UUID userId = waitingUser(request);
        SecondFactors.PasskeyChallenge challenge = factors.passkeyChallenge(userId)
                .orElseThrow(() -> new ConflictException(
                        "no-passkey", "There is no passkey to use here. Use a code or a recovery code instead."));
        sessions.awaitPasskey(userId, challenge.state(), request);
        return challenge.options();
    }

    /** The user whose password was right and who now owes a second factor; a sign-in or a step-up. */
    private UUID waitingUser(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StudioPrincipal current) {
            return sessions.pendingStepUp(request)
                    .filter(p -> p.userId().equals(current.userId()))
                    .map(SessionAuthentication.PendingStepUp::userId)
                    .orElseThrow(
                            () -> new ReauthenticationFailedException("Enter your password first, then your code."));
        }
        return sessions.pendingSecondFactor(request)
                .map(SessionAuthentication.PendingSecondFactor::userId)
                .orElseThrow(SignInExpiredException::new);
    }

    /** The proof a submission carries; a passkey answer is checked against the challenge this user was issued. */
    private SecondFactors.Proof proof(Submission submission, UUID userId, HttpServletRequest request) {
        boolean totp = submission.totpCode() != null && !submission.totpCode().isBlank();
        boolean recovery =
                submission.recoveryCode() != null && !submission.recoveryCode().isBlank();
        boolean passkey =
                submission.webauthn() != null && !submission.webauthn().isBlank();
        if ((totp ? 1 : 0) + (recovery ? 1 : 0) + (passkey ? 1 : 0) != 1) {
            throw new IllegalArgumentException("Send one of totpCode, recoveryCode or webauthn.");
        }
        if (totp) {
            return new SecondFactors.TotpCode(submission.totpCode());
        }
        if (recovery) {
            return new SecondFactors.RecoveryCode(submission.recoveryCode());
        }
        // Not a guess at anything, so it is not counted against the account: there was just nothing to answer.
        SessionAuthentication.PendingPasskey challenge = sessions.takePasskey(userId, request)
                .orElseThrow(() -> new SecondFactorInvalidException(
                        "The passkey prompt has expired. Choose “Use a passkey” to start it again."));
        return new SecondFactors.WebAuthnAssertion(challenge.challenge(), submission.webauthn());
    }

    /**
     * The second factor of a sign-in or a step-up. With no principal it completes the sign-in the
     * password started; with one, the step-up its password started. A wrong factor counts against the
     * account and the source address exactly as a wrong password does.
     */
    @Transactional
    public Outcome secondFactor(Submission submission, HttpServletRequest request, HttpServletResponse response) {
        SecondFactors factors = secondFactors.orElseThrow(SignInExpiredException::new);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof StudioPrincipal current
                ? completeStepUp(factors, current, submission, request, response)
                : completeSignIn(factors, submission, request, response);
    }

    private Outcome completeSignIn(
            SecondFactors factors, Submission submission, HttpServletRequest request, HttpServletResponse response) {
        var pending = sessions.pendingSecondFactor(request).orElseThrow(SignInExpiredException::new);
        UserAccounts.Account account = accounts.byId(pending.userId())
                .filter(a -> a.providerId().equals(pending.providerId()))
                .filter(a -> !a.disabled() && !lockout.isLocked(a.id()))
                .orElse(null);
        if (account == null) {
            // Locked or disabled since the password: answered as a wrong password, and the password is forgotten.
            sessions.clearPending(request);
            throw new BadCredentialsException("Invalid username or password");
        }
        SecondFactors.Proof proof = proof(submission, account.id(), request);
        SessionFacts.Method method = verified(factors, account.id(), account.username(), proof, request)
                .orElseThrow(() -> invalid(proof));
        StudioPrincipal principal = new StudioPrincipal(
                account.id(), account.username(), grants.loadFor(account.id()), account.mustChangePassword());
        sessions.establish(principal, SessionFacts.signedInWithSecondFactor(request, method), request, response);
        completed(principal, request);
        audit.secondFactorVerified(account.username(), method);
        audit.loginCompleted(pending.auditId());
        if (submission.trustDevice()) {
            trustBrowser(factors, principal, request, response);
        }
        return new Outcome.Authenticated(principal);
    }

    private Outcome completeStepUp(
            SecondFactors factors,
            StudioPrincipal current,
            Submission submission,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.pendingStepUp(request)
                .filter(p -> p.userId().equals(current.userId()))
                .orElseThrow(() -> new ReauthenticationFailedException("Enter your password first, then your code."));
        SecondFactors.Proof proof = proof(submission, current.userId(), request);
        Optional<SessionFacts.Method> method =
                verified(factors, current.userId(), current.getUsername(), proof, request);
        if (method.isEmpty()) {
            if (limiter.isLocked(current.getUsername(), request.getRemoteAddr())) {
                sessions.end(request, response);
                throw new BadCredentialsException("Too many failed attempts; the session was ended.");
            }
            throw invalid(proof);
        }
        limiter.recordSuccess(current.getUsername(), request.getRemoteAddr());
        sessions.reauthenticated(request, method.get());
        audit.secondFactorVerified(current.getUsername(), method.get());
        return new Outcome.Authenticated(current);
    }

    private static SecondFactorInvalidException invalid(SecondFactors.Proof proof) {
        return proof instanceof SecondFactors.WebAuthnAssertion
                ? new SecondFactorInvalidException(
                        "That passkey was not accepted. Try again, or use a code or a recovery code.")
                : new SecondFactorInvalidException();
    }

    /**
     * Throttles and checks one attempt. The result is empty only in step-up, when the caller should
     * end the session; a failed sign-in throws.
     */
    private Optional<SessionFacts.Method> verified(
            SecondFactors factors,
            UUID userId,
            String username,
            SecondFactors.Proof proof,
            HttpServletRequest request) {
        if (limiter.isLocked(username, request.getRemoteAddr())) {
            audit.secondFactorFailed(username, "throttled");
            throw new LoginThrottledException();
        }
        Optional<SessionFacts.Method> method = factors.verify(userId, proof);
        if (method.isEmpty()) {
            lockout.failed(userId, username, request);
            audit.secondFactorFailed(username, "invalid code");
        }
        return method;
    }

    /**
     * The one place a sign-in counts as a success: the whole sign-in finished, second factor
     * included. The second-factor step calls it once the factor verified; a correct password alone
     * never does.
     */
    public void completed(StudioPrincipal principal, HttpServletRequest request) {
        lockout.completed(principal.userId(), principal.getUsername(), request);
    }

    /**
     * Whether the browser presented a live trusted-device cookie for this user. Trusted devices switched
     * off (lifetime 0) means the cookie is ignored, and cleared so the browser stops sending it.
     */
    private boolean trustedDevice(StudioPrincipal user, HttpServletRequest request, HttpServletResponse response) {
        Optional<String> token = TrustedDeviceCookie.read(request);
        if (token.isEmpty()) {
            return false;
        }
        SecondFactors factors = secondFactors.orElseThrow();
        if (factors.trustedDeviceLifetime().isZero()) {
            TrustedDeviceCookie.clear(request, response);
            return false;
        }
        return factors.useTrustedDevice(user.userId(), token.get());
    }

    private static int trustDeviceDays(SecondFactors factors) {
        return (int) Math.ceilDiv(
                factors.trustedDeviceLifetime().toSeconds(), Duration.ofDays(1).toSeconds());
    }

    /** The user asked to trust this browser: remember it, if trusted devices are on, and hand the browser its cookie. */
    private void trustBrowser(
            SecondFactors factors, StudioPrincipal user, HttpServletRequest request, HttpServletResponse response) {
        Duration lifetime = factors.trustedDeviceLifetime();
        if (lifetime.isZero()) {
            return;
        }
        SessionFacts facts = sessions.facts(request).orElseThrow();
        String token = factors.trustDevice(user.userId(), facts.clientAddress(), facts.userAgent());
        TrustedDeviceCookie.set(request, response, token, lifetime);
    }

    /**
     * The account a failed attempt counts against: the one the provider signs in, so failing local
     * sign-ins for a single-sign-on user's name cannot lock that user out.
     */
    private UUID lockableAccount(String providerId, String username) {
        return accounts.byUsername(username)
                .filter(a -> a.providerId().equals(providerId))
                .map(UserAccounts.Account::id)
                .orElse(null);
    }

    /**
     * Step-up for a signed-in user whose provider checks a password (ADR-0103): the password must
     * identify this same user, and a user with a second factor then gives it to {@link #secondFactor}. Throttled like a login; the attempt that reaches the lockout also
     * ends the session, so a stolen cookie cannot be used to guess the password at leisure.
     */
    @Transactional
    public Outcome reauthenticate(
            StudioPrincipal current, String password, HttpServletRequest request, HttpServletResponse response) {
        String username = current.getUsername();
        String sourceIp = request.getRemoteAddr();
        if (limiter.isLocked(username, sourceIp)) {
            throw new LoginThrottledException();
        }
        UserAccounts.Account account = accounts.byId(current.userId())
                .orElseThrow(() -> new ReauthenticationFailedException("Your account no longer exists."));
        Optional<CredentialIdentityProvider> provider = credentialProvider(account.providerId());
        if (provider.isEmpty()) {
            throw new ReauthenticationFailedException(
                    "Your account signs in with " + account.providerId() + "; confirm it is you there instead.");
        }
        AuthenticationAudit.Attempt attempt = audit.reauthenticationAttempted(request);
        boolean same;
        try {
            same = provider.get()
                    .authenticate(username, password)
                    .map(p -> p.userId().equals(current.userId()))
                    .orElse(false);
        } catch (DisabledException _) {
            same = false;
        }
        if (!same) {
            limiter.recordFailure(username, sourceIp);
            attempt.failed(INVALID_CREDENTIALS);
            if (limiter.isLocked(username, sourceIp)) {
                sessions.end(request, response);
                throw new BadCredentialsException("Too many failed attempts; the session was ended.");
            }
            throw new ReauthenticationFailedException("That password is not right.");
        }
        attempt.succeeded();
        if (secondFactors.isPresent() && secondFactors.get().enrolled(current.userId())) {
            // The password alone never makes an account with a factor fresh, and does not clear its failures.
            sessions.awaitStepUp(current.userId(), request);
            return new Outcome.SecondFactorRequired(secondFactors.get().methods(current.userId()), 0);
        }
        limiter.recordSuccess(username, sourceIp);
        sessions.reauthenticated(request);
        return new Outcome.Authenticated(current);
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        audit.loggedOut();
        sessions.end(request, response);
    }

    private static String providerIdOrDefault(String providerId) {
        return providerId == null || providerId.isBlank() ? DEFAULT_PROVIDER : providerId.trim();
    }

    private Optional<CredentialIdentityProvider> credentialProvider(String providerId) {
        String id = providerIdOrDefault(providerId);
        return contributions.stream()
                .flatMap(c -> c.providers().stream())
                .filter(p -> p instanceof CredentialIdentityProvider && p.id().equals(id))
                .map(CredentialIdentityProvider.class::cast)
                .findFirst();
    }
}
