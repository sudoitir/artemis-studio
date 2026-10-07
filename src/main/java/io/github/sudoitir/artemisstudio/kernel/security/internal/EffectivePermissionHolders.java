package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who holds a permission, decided by the same {@link PermissionResolver} every request goes through, on
 * each enabled user's current access snapshot. It asks every enabled account in turn, so it costs one
 * check per user, and stops at the limit.
 */
@Component
@RequiredArgsConstructor
class EffectivePermissionHolders implements PermissionHolders {

    private final AppUserRepository users;
    private final PermissionResolver perm;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> holders(UUID clusterId, String permission, int limit) {
        return users.findByDisabledFalseOrderByCreatedAt().stream()
                .filter(u -> perm.can(StudioPrincipal.live(u.getId(), u.getUsername(), false), clusterId, permission))
                .map(AppUserRepository.EnabledAccount::getId)
                .limit(limit)
                .toList();
    }
}
