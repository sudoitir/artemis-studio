package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service password change for a local account (identity-and-sessions spec). */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccounts accounts;
    private final PasswordEncoder passwordEncoder;
    private final GrantLoader grantLoader;
    private final SessionAuthentication sessions;
    private final AuditService auditService;
    private final ActorResolver actorResolver;

    @Transactional
    public void changePassword(
            StudioPrincipal principal,
            String currentPassword,
            String newPassword,
            HttpServletRequest request,
            HttpServletResponse response) {
        Account user =
                accounts.byId(principal.userId()).orElseThrow(() -> new NotFoundException("user", principal.userId()));
        if (user.passwordHash() == null || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        accounts.changePassword(user.id(), passwordEncoder.encode(newPassword));
        auditService.succeed(
                auditService.begin(
                        actorResolver.resolve(), "PASSWORD_CHANGE", "user", user.username(), null, null, null, false),
                1);
        // The session's principal still carries the old mustChangePassword=true —
        // re-establish it with a fresh one so the must-change-password gate unlocks
        // immediately, without forcing a separate login.
        sessions.establish(
                new StudioPrincipal(user.id(), user.username(), grantLoader.loadFor(user.id()), false),
                request,
                response);
    }
}
