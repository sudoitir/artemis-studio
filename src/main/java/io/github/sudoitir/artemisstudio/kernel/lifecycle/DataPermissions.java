package io.github.sudoitir.artemisstudio.kernel.lifecycle;

/** Permission strings the data lifecycle checks (ADR-0038); declared in {@link LifecycleModule}. */
public final class DataPermissions {

    public static final String DATA_READ = "data:read";

    public static final String DATA_WRITE = "data:write";

    private DataPermissions() {}
}
