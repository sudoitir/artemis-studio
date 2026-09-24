package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Locale;

/** {@code plugin_install.status} (ADR-0099); the column stores the lowercase, underscored form. */
public enum PluginInstallStatus {
    ACTIVATING,
    ACTIVE,
    DISABLED,
    FAILED,
    INCOMPATIBLE,
    NEEDS_RESTART,
    UNINSTALLED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PluginInstallStatus fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
