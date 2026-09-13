package io.github.sudoitir.artemisstudio.kernel.audit.internal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AuthenticationAuditRecorder implements AuthenticationAudit {

    private final AuditService audit;
    private final ActorResolver actors;

    @Override
    public Attempt loginAttempted(String username, HttpServletRequest request) {
        // No session exists yet, so the actor is the anonymous caller at this address.
        Actor anonymous = new Actor(Actor.ANONYMOUS, request.getRemoteAddr(), request.getHeader("X-Request-Id"), null);
        AuditEvent event = audit.begin(anonymous, "LOGIN", "user", username, null, null, null, false);
        return new Attempt() {
            @Override
            public void failed(String reason) {
                audit.fail(event, reason);
            }

            @Override
            public void succeeded() {
                audit.succeed(event, 1);
            }
        };
    }

    @Override
    public void loggedOut() {
        Actor actor = actors.resolve();
        audit.succeed(audit.begin(actor, "LOGOUT", "user", actor.username(), null, null, null, false), 1);
    }
}
