package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SecretRotations;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Secret provider status and the start of a key rotation (ADR-0132 D5). The start checks its permission and fresh
 * authentication itself, inside the audited action, so a refusal is recorded like a success (ADR-0078). The audit
 * parameters carry versions only.
 */
@Service
@RequiredArgsConstructor
public class SecretRotationService {

    private final SecretRotations rotations;
    private final SecretVault vault;
    private final AuditService audit;
    private final ActorResolver actors;
    private final PermissionResolver permissions;
    private final SessionAuthentication sessions;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions).SETTINGS_READ)")
    public SecretRotations.Status status() {
        return rotations.status();
    }

    /** Starts a rotation to the newest key version; the sweep job finishes it. */
    public SecretRotations.Rotation start(HttpServletRequest request) {
        Actor actor = actors.resolve();
        AuditEvent event = audit.begin(
                actor,
                "SECRET_ROTATION_START",
                "secrets",
                "key-encryption-key",
                null,
                null,
                Map.of("currentVersion", vault.currentKekVersion()),
                false);
        try {
            if (!permissions.can(SettingsPermissions.SETTINGS_WRITE)) {
                throw new AccessDeniedException("Starting a key rotation needs the settings:write permission.");
            }
            if (!sessions.recentlyAuthenticated(request)) {
                throw new ReauthenticationRequiredException();
            }
            SecretRotations.Rotation rotation = rotations.start(actor.displayName());
            audit.succeed(event, 1);
            return rotation;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
