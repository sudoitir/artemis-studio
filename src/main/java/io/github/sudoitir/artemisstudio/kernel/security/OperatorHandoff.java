package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Hands the operator behind a request to work that outlives it, such as a bulk run (ADR-0093). A
 * feature may not touch Spring Security itself, so it captures an {@link Operator} on the request
 * thread and runs each step through {@link #runAs}: there the permission guards see the operator's
 * principal and every audit row names them, with the request's id and source address.
 */
@Component
@RequiredArgsConstructor
public class OperatorHandoff {

    private final ActorResolver actors;
    private final UserAccounts accounts;
    private final GrantLoader grants;
    private final PermissionResolver perm;

    /** Who started the work: their principal as authenticated, and the actor their audit rows carry. */
    public record Operator(StudioPrincipal principal, Actor actor) {}

    /** On the request thread. Fails when no one is signed in: there is no one to act for. */
    public Operator capture() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof StudioPrincipal principal)) {
            throw new IllegalStateException("There is no signed-in operator to act for.");
        }
        return new Operator(principal, actors.resolve());
    }

    /**
     * The user as their account stands now, to act for work that has no request: grants read from the
     * database, not from a session. Empty for a null id, an unknown user or a disabled one.
     */
    public Optional<Operator> forUser(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return accounts.byId(userId).filter(account -> !account.disabled()).map(account -> {
            StudioPrincipal principal =
                    new StudioPrincipal(account.id(), account.username(), grants.loadFor(account.id()), false);
            return new Operator(principal, new Actor(account.username(), null, null, account.id()));
        });
    }

    /** Run {@code task} on this thread as the operator, leaving the thread as it was afterwards. */
    public void runAs(Operator operator, Runnable task) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        StudioPrincipal principal = operator.principal();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        ScopedValue.where(ActorResolver.ON_BEHALF_OF, operator.actor())
                .run(new DelegatingSecurityContextRunnable(task, context));
    }

    /** As {@link #runAs}, returning what {@code task} returns. */
    public <T> T callAs(Operator operator, Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        runAs(operator, () -> result.set(task.get()));
        return result.get();
    }

    /**
     * Whether the operator still holds {@code permission}: as they were authenticated, and as their
     * account's grants stand now. A principal's grants are loaded once, at sign-in, so a grant
     * withdrawn while long-running work proceeds is only seen by reading the account again. A
     * principal with no account has nothing to re-read, and is refused.
     */
    public boolean stillHolds(Operator operator, UUID clusterId, String permission) {
        StudioPrincipal captured = operator.principal();
        if (captured.userId() == null || !perm.can(captured, clusterId, permission)) {
            return false;
        }
        StudioPrincipal current = new StudioPrincipal(
                captured.userId(), captured.getUsername(), grants.loadFor(captured.userId()), false);
        return perm.can(current, clusterId, permission);
    }
}
