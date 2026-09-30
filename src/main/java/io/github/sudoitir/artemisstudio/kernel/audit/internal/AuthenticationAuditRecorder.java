package io.github.sudoitir.artemisstudio.kernel.audit.internal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.RequestIds;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.AuthenticationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AuthenticationAuditRecorder implements AuthenticationAudit {

    private final AuditService audit;
    private final ActorResolver actors;

    @Override
    public Attempt loginAttempted(String username, HttpServletRequest request) {
        return attempt(audit.begin(anonymous(request), "LOGIN", "user", username, null, null, null, false));
    }

    @Override
    public void accountLocked(String username, HttpServletRequest request) {
        audit.succeed(audit.begin(anonymous(request), "ACCOUNT_LOCK", "user", username, null, null, null, false), 1);
    }

    @Override
    public void secondFactorFailed(String username, String reason) {
        audit.succeed(
                audit.begin(
                        actors.resolve(),
                        "SECOND_FACTOR_FAILED",
                        "user",
                        username,
                        null,
                        null,
                        Map.of("reason", reason),
                        false),
                1);
    }

    @Override
    public void secondFactorVerified(String username, SessionFacts.Method method) {
        audit.succeed(
                audit.begin(
                        actors.resolve(),
                        "SECOND_FACTOR",
                        "user",
                        username,
                        null,
                        null,
                        Map.of("method", method.name()),
                        false),
                1);
    }

    /** No session exists yet, so the actor is the anonymous caller at this address. */
    private static Actor anonymous(HttpServletRequest request) {
        return new Actor(Actor.ANONYMOUS, request.getRemoteAddr(), RequestIds.of(request), null);
    }

    @Override
    public Attempt reauthenticationAttempted(HttpServletRequest request) {
        Actor actor = actors.resolve();
        return attempt(audit.begin(actor, "REAUTHENTICATE", "user", actor.username(), null, null, null, false));
    }

    private Attempt attempt(AuditEvent event) {
        return new Attempt() {
            @Override
            public void failed(String reason) {
                audit.fail(event, reason);
            }

            @Override
            public void succeeded() {
                audit.succeed(event, 1);
            }

            @Override
            public long awaitingSecondFactor() {
                audit.fail(event, "password accepted, second factor not given");
                return event.getId();
            }
        };
    }

    @Override
    public void loginCompleted(long attemptId) {
        audit.byId(attemptId).ifPresent(attempt -> audit.succeed(attempt, 1));
    }

    @Override
    public void loggedOut() {
        Actor actor = actors.resolve();
        audit.succeed(audit.begin(actor, "LOGOUT", "user", actor.username(), null, null, null, false), 1);
    }
}
