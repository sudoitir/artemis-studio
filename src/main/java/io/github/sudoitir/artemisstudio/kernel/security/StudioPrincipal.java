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
 * The authenticated principal for both session and API-token requests
 * (design.md decision 3): a user id, username, and the resolved set of scoped
 * {@link Grant}s to check against. Built once per authentication, not once per
 * permission check.
 */
@PluginApi
public class StudioPrincipal extends User {

    private final UUID userId;
    private final Set<Grant> grants;
    private final boolean mustChangePassword;
    private final String tokenName;
    private final boolean secondFactorEnrolmentRequired;

    public StudioPrincipal(UUID userId, String username, Set<Grant> grants, boolean mustChangePassword) {
        this(userId, username, grants, mustChangePassword, null);
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
        this.grants = grants;
        this.mustChangePassword = mustChangePassword;
        this.tokenName = tokenName;
        this.secondFactorEnrolmentRequired = secondFactorEnrolmentRequired;
    }

    /** The same principal with the enrolment restriction set or lifted. */
    public StudioPrincipal withSecondFactorEnrolmentRequired(boolean required) {
        return new StudioPrincipal(userId, getUsername(), grants, mustChangePassword, tokenName, required);
    }

    public boolean secondFactorEnrolmentRequired() {
        return secondFactorEnrolmentRequired;
    }

    public String tokenName() {
        return tokenName;
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
