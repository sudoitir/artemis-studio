package io.github.sudoitir.artemisstudio.security;

import java.util.UUID;

/** The nil UUID `003-identity.sql` and `014-identity.sql` use as the scope id for a GLOBAL-scoped row. */
public final class ScopeIds {

    public static final UUID GLOBAL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private ScopeIds() {}
}
