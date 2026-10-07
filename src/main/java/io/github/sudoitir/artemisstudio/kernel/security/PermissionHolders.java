package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.List;
import java.util.UUID;

public interface PermissionHolders {

    /** Enabled users who hold a permission at a cluster scope (null for global). */
    List<UUID> holders(UUID clusterId, String permission, int limit);
}
