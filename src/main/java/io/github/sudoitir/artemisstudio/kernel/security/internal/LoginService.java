package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.AccountLockout;
import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.LoginAttemptLimiter;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationFailedException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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

    @Transactional
    public StudioPrincipal login(
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
        if (lockedOut(principal.get())) {
            // Answered exactly like a wrong password, so a locked account is not told apart.
            lockout.failed(principal.get().userId(), username, request);
            attempt.failed("account locked");
            throw new BadCredentialsException("Invalid username or password");
        }
        sessions.establish(principal.get(), SessionFacts.signedIn(request), request, response);
        completed(principal.get(), request);
        attempt.succeeded();
        return principal.get();
    }

    /**
     * The one place a sign-in counts as a success: the whole sign-in finished, second factor
     * included. The second-factor step calls it once the factor verified; a correct password alone
     * never does.
     */
    public void completed(StudioPrincipal principal, HttpServletRequest request) {
        lockout.completed(principal.userId(), principal.getUsername(), request);
    }

    /** The one place the account lock is enforced for a sign-in that presented the right password. */
    private boolean lockedOut(StudioPrincipal principal) {
        // A valid trusted-device cookie for this user will exempt them here (task 6.6).
        return lockout.isLocked(principal.userId());
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
     * identify this same user. Throttled like a login; the attempt that reaches the lockout also
     * ends the session, so a stolen cookie cannot be used to guess the password at leisure.
     */
    @Transactional
    public void reauthenticate(
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
        limiter.recordSuccess(username, sourceIp);
        sessions.reauthenticated(request);
        attempt.succeeded();
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
