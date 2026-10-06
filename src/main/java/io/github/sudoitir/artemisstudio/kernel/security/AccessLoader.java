package io.github.sudoitir.artemisstudio.kernel.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The current {@link AccessSnapshot} of a user, read from the database and kept per user. Every change to
 * what a snapshot is built from goes through {@link AccessChanges}, which drops it on every replica; the
 * short expiry only bounds the damage of a message a replica missed.
 */
@Component
@RequiredArgsConstructor
public class AccessLoader {

    private static final Duration EXPIRY = Duration.ofMinutes(1);

    private final AppUserRepository users;
    private final GrantLoader grants;
    private final TeamMemberRepository members;
    private final RolePermissionRepository rolePermissions;

    private final Cache<UUID, AccessSnapshot> cache =
            Caffeine.newBuilder().expireAfterWrite(EXPIRY).maximumSize(10_000).build();

    public AccessSnapshot of(UUID userId) {
        return cache.get(userId, this::load);
    }

    /** Forget one user's snapshot, or every snapshot when {@code userId} is null. */
    void invalidate(UUID userId) {
        if (userId == null) {
            cache.invalidateAll();
        } else {
            cache.invalidate(userId);
        }
    }

    private AccessSnapshot load(UUID userId) {
        if (users.findById(userId).filter(u -> !u.isDisabled()).isEmpty()) {
            return AccessSnapshot.NONE;
        }
        Map<UUID, Set<String>> teams = new HashMap<>();
        Map<UUID, Set<String>> byRole = new HashMap<>();
        for (TeamMemberEntity member : members.findHeldBy(userId)) {
            Set<String> permissions = byRole.computeIfAbsent(
                    member.getRoleId(),
                    role -> rolePermissions.findByIdRoleId(role).stream()
                            .map(RolePermissionEntity::getAction)
                            .collect(java.util.stream.Collectors.toSet()));
            teams.computeIfAbsent(member.getTeamId(), t -> new HashSet<>()).addAll(permissions);
        }
        return new AccessSnapshot(grants.loadFor(userId), teams);
    }
}
