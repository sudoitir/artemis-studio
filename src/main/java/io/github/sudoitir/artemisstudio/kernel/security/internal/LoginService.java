package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.LoginThrottledException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one username-and-password login path (identity-and-sessions spec). It throttles, audits
 * the attempt, asks the named credential provider — {@code local} when none is named — and
 * starts the session. A provider that is not configured fails exactly like a wrong password,
 * so a login request cannot probe which providers exist.
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    public static final String DEFAULT_PROVIDER = "local";

    private final List<IdentityProviders> contributions;
    private final LoginAttemptLimiter limiter;
    private final SessionAuthentication sessions;
    private final AuthenticationAudit audit;

    @Transactional
    public StudioPrincipal login(
            String providerId,
            String username,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        String sourceIp = request.getRemoteAddr();
        if (limiter.isLocked(username, sourceIp)) {
            throw new LoginThrottledException();
        }
        AuthenticationAudit.Attempt attempt = audit.loginAttempted(username, request);
        Optional<StudioPrincipal> principal;
        try {
            principal = credentialProvider(providerId).flatMap(p -> p.authenticate(username, password));
        } catch (DisabledException e) {
            limiter.recordFailure(username, sourceIp);
            attempt.failed("invalid credentials");
            throw e;
        }
        if (principal.isEmpty()) {
            limiter.recordFailure(username, sourceIp);
            attempt.failed("invalid credentials");
            throw new BadCredentialsException("Invalid username or password");
        }
        limiter.recordSuccess(username, sourceIp);
        sessions.establish(principal.get(), request, response);
        attempt.succeeded();
        return principal.get();
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        audit.loggedOut();
        sessions.end(request, response);
    }

    private Optional<CredentialIdentityProvider> credentialProvider(String providerId) {
        String id = providerId == null || providerId.isBlank() ? DEFAULT_PROVIDER : providerId.trim();
        return contributions.stream()
                .flatMap(c -> c.providers().stream())
                .filter(p -> p instanceof CredentialIdentityProvider && p.id().equals(id))
                .map(CredentialIdentityProvider.class::cast)
                .findFirst();
    }
}
