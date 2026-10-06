package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserGroupEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserGroupRepository;
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
 * provider's default role, or is refused when it has none, unless they are a team member, directly or
 * through one of their groups. The groups they were in are kept, so teams that hold a group reach its users.
 */
@Component
@RequiredArgsConstructor
public class IdentityProvisioner {

    private final AppUserRepository users;
    private final GroupMappingRepository mappings;
    private final DefaultRoleRepository defaultRoles;
    private final UserRoleRepository userRoles;
    private final UserGroupRepository userGroups;
    private final TeamMemberRepository teamMembers;
    private final AccessChanges accessChanges;

    /**
     * The signed-in principal, or empty when the user is disabled, or no mapping or default role applies and
     * they are in no team.
     */
    @Transactional
    public Optional<StudioPrincipal> provision(ExternalIdentity identity) {
        AppUserEntity user = users.findByProviderIdAndExternalSubject(identity.providerId(), identity.subject())
                .orElseGet(() -> users.save(AppUserEntity.external(
                        identity.providerId(), identity.subject(), freeUsername(identity), identity.email())));
        if (user.isDisabled()) {
            return Optional.empty();
        }
        replaceGroups(user.getId(), identity);
        boolean holdsRole = applyMappings(user.getId(), identity);
        accessChanges.changedFor(user.getId());
        if (!holdsRole && !teamMembers.isMemberOfAny(user.getId())) {
            return Optional.empty();
        }
        return Optional.of(StudioPrincipal.live(user.getId(), user.getUsername(), false));
    }

    /**
     * Usernames are unique across providers, ignoring case, so a name already taken is qualified with the
     * provider id.
     */
    private String freeUsername(ExternalIdentity identity) {
        return users.existsByUsernameIgnoreCase(identity.username())
                ? identity.username() + "@" + identity.providerId()
                : identity.username();
    }

    /**
     * ponytail: a grant counts as mapping-derived when its (role, scope) matches one of the
     * provider's mappings; there is no "derived" flag on {@code user_role}. A hand-made grant
     * identical to a mapping's is removed with it. Add a column if that ever matters.
     *
     * @return false when no mapping matched and the provider has no default role, so the user holds no role
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
            defaultRoles
                    .findById(identity.providerId())
                    .map(DefaultRoleEntity::getRoleId)
                    .ifPresent(fallback -> desired.add(key(userId, fallback, "GLOBAL", ScopeIds.GLOBAL)));
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
        return !desired.isEmpty();
    }

    /** The user's groups become exactly the ones the provider reported now. */
    private void replaceGroups(UUID userId, ExternalIdentity identity) {
        Set<String> reported = identity.groups() == null ? Set.of() : identity.groups();
        Set<String> kept = new HashSet<>();
        for (UserGroupEntity stored : userGroups.findByIdUserId(userId)) {
            if (stored.getProviderId().equals(identity.providerId()) && reported.contains(stored.getGroupName())) {
                kept.add(stored.getGroupName());
            } else {
                userGroups.delete(stored);
            }
        }
        reported.stream()
                .filter(group -> !kept.contains(group))
                .forEach(group -> userGroups.save(new UserGroupEntity(userId, identity.providerId(), group)));
    }

    private static UserRoleEntity.Key key(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
        return new UserRoleEntity(userId, roleId, scopeType, scopeId).getId();
    }
}
