package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.UUID;

/** The nil UUID `0001-baseline.sql` (kernel/security) uses as the scope id for a GLOBAL-scoped row. */
public final class ScopeIds {

    public static final UUID GLOBAL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private ScopeIds() {}
}
