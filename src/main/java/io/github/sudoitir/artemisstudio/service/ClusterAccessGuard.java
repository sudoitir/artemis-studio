package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.security.PermissionResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Guards a per-cluster operation. A caller with no grant on the target cluster
 * gets a {@link NotFoundException} (404), not a permission-denied response —
 * revealing whether a cluster id exists to someone with no grant on it would
 * leak information the authorization spec says must stay hidden. Used instead
 * of {@code @PreAuthorize} on any method addressed by a specific cluster id;
 * {@code @PreAuthorize} stays for operations with no cluster to hide (global
 * writes, settings, user/role/environment administration).
 */
@Component
@RequiredArgsConstructor
public class ClusterAccessGuard {

    private final PermissionResolver perm;

    public void requireCluster(UUID clusterId, String permission) {
        if (!perm.can(clusterId, permission)) {
            throw new NotFoundException("cluster", clusterId);
        }
    }
}
