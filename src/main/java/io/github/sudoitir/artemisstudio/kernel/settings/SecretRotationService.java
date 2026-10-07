package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.gate.Gated;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Secret provider status and the start of a key rotation (ADR-0132 D5). The start checks its permission and fresh
 * authentication itself and audits a refusal like a success (ADR-0078); the rotation then passes the approval gate. The
 * audit parameters carry versions only.
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
    private final ObjectProvider<OperationGate> gate;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions).SETTINGS_READ)")
    public SecretRotations.Status status() {
        return rotations.status();
    }

    /**
     * Starts a rotation on a request from a signed-in session: needs the permission and a fresh authentication, which
     * only the request can show, then goes through {@link #rotate}.
     */
    public SecretRotations.Rotation start(HttpServletRequest request) {
        Actor actor = actors.resolve();
        requireWrite(actor);
        if (!sessions.recentlyAuthenticated(request)) {
            throw refused(actor, new ReauthenticationRequiredException());
        }
        return rotate();
    }

    /**
     * Starts a rotation to the newest key version; the sweep job finishes it. It is the gated operation {@code
     * secrets.rotate}: with an approval provider installed it is held until a second person approves it.
     */
    @Gated(RotateSecretsOperation.TYPE)
    public SecretRotations.Rotation rotate() {
        Actor actor = actors.resolve();
        requireWrite(actor);
        return gate.getObject().run(Operation.of(new RotateSecrets()), () -> begin(actor));
    }

    private SecretRotations.Rotation begin(Actor actor) {
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
            SecretRotations.Rotation rotation = rotations.start(actor.displayName());
            audit.succeed(event, 1);
            return rotation;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private void requireWrite(Actor actor) {
        if (!permissions.can(SettingsPermissions.SETTINGS_WRITE)) {
            throw refused(
                    actor, new AccessDeniedException("Starting a key rotation needs the settings:write permission."));
        }
    }

    /** Records a refusal like a success (ADR-0078), then hands the exception back to throw. */
    private <E extends RuntimeException> E refused(Actor actor, E refusal) {
        AuditEvent event = audit.begin(
                actor,
                "SECRET_ROTATION_START",
                "secrets",
                "key-encryption-key",
                null,
                null,
                Map.of("currentVersion", vault.currentKekVersion()),
                false);
        audit.fail(event, refusal.getMessage());
        return refusal;
    }
}
