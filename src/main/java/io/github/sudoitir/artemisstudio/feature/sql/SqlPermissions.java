package io.github.sudoitir.artemisstudio.feature.sql;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class SqlPermissions {

    /**
     * Turning message capture on. Not {@code settings:write}: capture mutates broker
     * routing on a schedule and creates a second copy of production payload, which is
     * a different authority from changing how often Studio polls.
     */
    public static final String CAPTURE_WRITE = "capture:write";

    private SqlPermissions() {}
}
