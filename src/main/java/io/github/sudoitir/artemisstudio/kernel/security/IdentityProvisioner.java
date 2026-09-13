package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an identity established by an external provider into a Studio user (ADR-0073). Users
 * are keyed by provider and subject, so the same subject from two providers is two accounts.
 * The provider's group mappings are re-applied at every sign-in, so a change in group
 * membership takes effect at the next one; a user whose groups match no mapping gets the
 * provider's default role, or is refused when it has none.
 */
@Component
@RequiredArgsConstructor
public class IdentityProvisioner {

    private final AppUserRepository users;
    private final GroupMappingRepository mappings;
    private final DefaultRoleRepository defaultRoles;
    private final UserRoleRepository userRoles;
    private final GrantLoader grants;

    /** The signed-in principal, or empty when the user is disabled or no mapping or default role applies. */
    @Transactional
    public Optional<StudioPrincipal> provision(ExternalIdentity identity) {
        AppUserEntity user = users.findByProviderIdAndExternalSubject(identity.providerId(), identity.subject())
                .orElseGet(() -> users.save(AppUserEntity.external(
                        identity.providerId(), identity.subject(), freeUsername(identity), identity.email())));
        if (user.isDisabled() || !applyMappings(user.getId(), identity)) {
            return Optional.empty();
        }
        return Optional.of(new StudioPrincipal(user.getId(), user.getUsername(), grants.loadFor(user.getId()), false));
    }

    /** Usernames are unique across providers, so a name already taken is qualified with the provider id. */
    private String freeUsername(ExternalIdentity identity) {
        return users.findByUsername(identity.username()).isPresent()
                ? identity.username() + "@" + identity.providerId()
                : identity.username();
    }

    /**
     * ponytail: a grant counts as mapping-derived when its (role, scope) matches one of the
     * provider's mappings; there is no "derived" flag on {@code user_role}. A hand-made grant
     * identical to a mapping's is removed with it. Add a column if that ever matters.
     *
     * @return false when no mapping matched and the provider has no default role
     */
    private boolean applyMappings(UUID userId, ExternalIdentity identity) {
        Set<UserRoleEntity.Key> mapped = new HashSet<>();
        Set<UserRoleEntity.Key> desired = new HashSet<>();
        for (GroupMappingEntity m : mappings.findByProviderId(identity.providerId())) {
            UserRoleEntity.Key key = key(userId, m.getRoleId(), m.getScopeType(), m.getScopeId());
            mapped.add(key);
            if (identity.groups().contains(m.getGroupName())) {
                desired.add(key);
            }
        }
        if (desired.isEmpty()) {
            Optional<UUID> fallback =
                    defaultRoles.findById(identity.providerId()).map(DefaultRoleEntity::getRoleId);
            if (fallback.isEmpty()) {
                return false;
            }
            desired.add(key(userId, fallback.get(), "GLOBAL", ScopeIds.GLOBAL));
        }
        for (UserRoleEntity existing : userRoles.findByIdUserId(userId)) {
            if (mapped.contains(existing.getId()) && !desired.contains(existing.getId())) {
                userRoles.deleteById(existing.getId());
            }
        }
        for (UserRoleEntity.Key key : desired) {
            if (userRoles.findById(key).isEmpty()) {
                userRoles.save(
                        new UserRoleEntity(key.getUserId(), key.getRoleId(), key.getScopeType(), key.getScopeId()));
            }
        }
        return true;
    }

    private static UserRoleEntity.Key key(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
        return new UserRoleEntity(userId, roleId, scopeType, scopeId).getId();
    }
}
