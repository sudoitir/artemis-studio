package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.List;
import java.util.UUID;

/** Finds the users who hold a permission, for features that notify them. */
public interface PermissionHolders {

    /**
     * The ids of the enabled users who hold {@code permission} on {@code clusterId} (or globally, when
     * {@code clusterId} is null) as their live principals would, at most {@code limit} of them.
     */
    List<UUID> holders(UUID clusterId, String permission, int limit);
}
