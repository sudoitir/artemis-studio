package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal for both session and API-token requests (design.md decision 3): a user
 * id and username. What the user may do is not stored in it, so it cannot go stale in a session: the
 * {@link PermissionResolver} reads the user's current {@link AccessSnapshot}. The exception is a
 * principal that carries its own grants ({@link #pinned()}), such as an API key, which acts within the
 * grants it was authenticated with.
 */
@PluginApi
public class StudioPrincipal extends User {

    private final UUID userId;
    private final Set<Grant> pinnedGrants;
    private final boolean mustChangePassword;
    private final String tokenName;
    private final boolean secondFactorEnrolmentRequired;

    public StudioPrincipal(UUID userId, String username, Set<Grant> grants, boolean mustChangePassword) {
        this(userId, username, grants, mustChangePassword, null);
    }

    /** A principal whose grants are read from the user's current access at every check. */
    public static StudioPrincipal live(UUID userId, String username, boolean mustChangePassword) {
        return new StudioPrincipal(userId, username, null, mustChangePassword, null, false);
    }

    /** {@code tokenName} is non-null only when authenticated via an API token (api-tokens spec). */
    public StudioPrincipal(
            UUID userId, String username, Set<Grant> grants, boolean mustChangePassword, String tokenName) {
        this(userId, username, grants, mustChangePassword, tokenName, false);
    }

    /**
     * {@code secondFactorEnrolmentRequired} is true for a session whose user must hold a second factor and
     * has none yet (ADR-0143): it may do nothing but enrol one.
     */
    public StudioPrincipal(
            UUID userId,
            String username,
            Set<Grant> grants,
            boolean mustChangePassword,
            String tokenName,
            boolean secondFactorEnrolmentRequired) {
        super(username, "", authorities(grants));
        this.userId = userId;
        this.pinnedGrants = grants;
        this.mustChangePassword = mustChangePassword;
        this.tokenName = tokenName;
        this.secondFactorEnrolmentRequired = secondFactorEnrolmentRequired;
    }

    /** The same principal with the enrolment restriction set or lifted. */
    public StudioPrincipal withSecondFactorEnrolmentRequired(boolean required) {
        return new StudioPrincipal(userId, getUsername(), pinnedGrants, mustChangePassword, tokenName, required);
    }

    public boolean secondFactorEnrolmentRequired() {
        return secondFactorEnrolmentRequired;
    }

    public String tokenName() {
        return tokenName;
    }

    private static Collection<? extends GrantedAuthority> authorities(Set<Grant> grants) {
        if (grants == null) {
            return List.of();
        }
        return grants.stream()
                .flatMap(g -> g.permissions().stream())
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    public UUID userId() {
        return userId;
    }

    /** Whether this principal acts within grants of its own rather than the user's current access. */
    public boolean pinned() {
        return pinnedGrants != null;
    }

    /** The grants a {@link #pinned()} principal carries; absent for one that follows the user's access. */
    public Set<Grant> pinnedGrants() {
        return pinnedGrants;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }
}
