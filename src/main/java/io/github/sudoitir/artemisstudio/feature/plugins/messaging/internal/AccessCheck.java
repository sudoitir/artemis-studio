package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether a user may use a cluster for a registration or a send, as their account stands now
 * (ADR-0111). Grants are read from the database every time, as {@code OperatorHandoff} does for
 * long-running work: a registration outlives any session, and a withdrawn grant must stop it.
 */
@Component
@RequiredArgsConstructor
public class AccessCheck {

    private final UserAccounts accounts;
    private final GrantLoader grants;
    private final PermissionResolver perm;

    /** Why the user may not, in words, or empty when they may. */
    public Optional<String> denial(UUID userId, UUID clusterId, List<String> permissions) {
        if (userId == null) {
            return Optional.of("No acting user was given, and every registration and send acts for one.");
        }
        Optional<UserAccounts.Account> account = accounts.byId(userId);
        if (account.isEmpty()) {
            return Optional.of("The acting user " + userId + " no longer exists.");
        }
        if (account.get().disabled()) {
            return Optional.of("The acting user '" + account.get().username() + "' is disabled.");
        }
        StudioPrincipal principal =
                new StudioPrincipal(userId, account.get().username(), grants.loadFor(userId), false);
        for (String permission : permissions) {
            if (!perm.can(principal, clusterId, permission)) {
                return Optional.of("The acting user '" + account.get().username() + "' does not hold " + permission
                        + " on cluster " + clusterId + ".");
            }
        }
        return Optional.empty();
    }

    /** The acting user's name, for audit rows, or their id when the account is gone. */
    public String nameOf(UUID userId) {
        return userId == null
                ? "unknown"
                : accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
    }
}
