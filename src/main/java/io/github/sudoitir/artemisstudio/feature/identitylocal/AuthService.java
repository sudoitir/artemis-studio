package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
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

    private final AppUserRepository users;
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
        AppUserEntity user =
                users.findById(principal.userId()).orElseThrow(() -> new NotFoundException("user", principal.userId()));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
        auditService.succeed(
                auditService.begin(
                        actorResolver.resolve(),
                        "PASSWORD_CHANGE",
                        "user",
                        user.getUsername(),
                        null,
                        null,
                        null,
                        false),
                1);
        // The session's principal still carries the old mustChangePassword=true —
        // re-establish it with a fresh one so the must-change-password gate unlocks
        // immediately, without forcing a separate login.
        sessions.establish(
                new StudioPrincipal(user.getId(), user.getUsername(), grantLoader.loadFor(user.getId()), false),
                request,
                response);
    }
}
