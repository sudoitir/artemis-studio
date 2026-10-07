package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.gate.GateScope;
import io.github.sudoitir.artemisstudio.kernel.gate.GateTicket;
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
    private final PermissionResolver perm;

    /**
     * Who started the work: their principal as authenticated, the actor their audit rows carry, and the
     * approval gate's ticket when the work runs under one (null otherwise), so the items of a bulk run
     * keep the coverage of the operation that started it.
     */
    public record Operator(StudioPrincipal principal, Actor actor, GateTicket covered) {}

    /** On the request thread. Fails when no one is signed in: there is no one to act for. */
    public Operator capture() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof StudioPrincipal principal)) {
            throw new IllegalStateException("There is no signed-in operator to act for.");
        }
        return new Operator(principal, actors.resolve(), GateScope.COVERED.isBound() ? GateScope.COVERED.get() : null);
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
            StudioPrincipal principal = StudioPrincipal.live(account.id(), account.username(), false);
            return new Operator(principal, new Actor(account.username(), null, null, account.id()), null);
        });
    }

    /** Run {@code task} on this thread as the operator, leaving the thread as it was afterwards. */
    public void runAs(Operator operator, Runnable task) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        StudioPrincipal principal = operator.principal();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        ScopedValue.Carrier carrier = ScopedValue.where(ActorResolver.ON_BEHALF_OF, operator.actor());
        if (operator.covered() != null) {
            carrier = carrier.where(GateScope.COVERED, operator.covered());
        }
        carrier.run(new DelegatingSecurityContextRunnable(task, context));
    }

    /** As {@link #runAs}, returning what {@code task} returns. */
    public <T> T callAs(Operator operator, Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        runAs(operator, () -> result.set(task.get()));
        return result.get();
    }

    /**
     * Whether the operator still holds {@code permission}: as they were authenticated, and as their
     * account's access stands now. An operator that carries grants of its own (an API key's) is
     * narrowed by them, so a grant withdrawn from the account while long-running work proceeds is only
     * seen by checking the account as well. A principal with no account is refused.
     */
    public boolean stillHolds(Operator operator, UUID clusterId, String permission) {
        StudioPrincipal captured = operator.principal();
        if (captured.userId() == null || !perm.can(captured, clusterId, permission)) {
            return false;
        }
        return perm.can(StudioPrincipal.live(captured.userId(), captured.getUsername(), false), clusterId, permission);
    }

    /** {@link #stillHolds(Operator, UUID, String)} for the permission on one queue or address. */
    public boolean stillHolds(Operator operator, UUID clusterId, ResourceRef resource, String permission) {
        StudioPrincipal captured = operator.principal();
        if (captured.userId() == null || !perm.can(captured, clusterId, resource, permission)) {
            return false;
        }
        return perm.can(
                StudioPrincipal.live(captured.userId(), captured.getUsername(), false),
                clusterId,
                resource,
                permission);
    }
}
