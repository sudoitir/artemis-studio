package io.github.sudoitir.artemisstudio.kernel.security;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class SettingsPermissions {

    public static final String SETTINGS_READ = "settings:read";

    public static final String SETTINGS_WRITE = "settings:write";

    private SettingsPermissions() {}
}
