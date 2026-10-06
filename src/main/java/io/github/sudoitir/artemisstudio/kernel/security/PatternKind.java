package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import java.util.EnumSet;
import java.util.Set;

/** What a team pattern or a share covers: queue names, address names, or both. */
public enum PatternKind {
    QUEUE(EnumSet.of(ResourceKind.QUEUE)),
    ADDRESS(EnumSet.of(ResourceKind.ADDRESS)),
    BOTH(EnumSet.allOf(ResourceKind.class));

    private final Set<ResourceKind> kinds;

    PatternKind(Set<ResourceKind> kinds) {
        this.kinds = kinds;
    }

    /** The resource kinds whose names it covers. */
    public Set<ResourceKind> kinds() {
        return kinds;
    }
}
