package io.github.sudoitir.artemisstudio.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal for both session and API-token requests
 * (design.md decision 3): a user id, username, and the resolved set of scoped
 * {@link Grant}s to check against. Built once per authentication, not once per
 * permission check.
 */
public class StudioPrincipal extends User {

    private final UUID userId;
    private final Set<Grant> grants;
    private final boolean mustChangePassword;

    public StudioPrincipal(UUID userId, String username, Set<Grant> grants, boolean mustChangePassword) {
        super(username, "", authorities(grants));
        this.userId = userId;
        this.grants = grants;
        this.mustChangePassword = mustChangePassword;
    }

    private static Collection<? extends GrantedAuthority> authorities(Set<Grant> grants) {
        return grants.stream()
                .flatMap(g -> g.permissions().stream())
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    public UUID userId() {
        return userId;
    }

    public Set<Grant> grants() {
        return grants;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    /** Convenience for tests and the {@code /me} view. */
    public List<Grant> grantList() {
        return grants.stream().toList();
    }
}
