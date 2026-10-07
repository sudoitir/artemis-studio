package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Arrays;
import java.util.List;
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

    /**
     * Whether an install in this status is meant to be running: everything but {@code disabled} and
     * {@code uninstalled}, which an administrator chose. A plugin that is starting, has failed, needs
     * a restart or is incompatible with this Studio build is still wanted, and safe mode leaves rows
     * as they are. The approval gate stays armed in all of them, so gated operations fail closed.
     */
    public boolean desiredActive() {
        return this != DISABLED && this != UNINSTALLED;
    }

    /** The {@link #dbValue() column values} of every status that is {@link #desiredActive()}. */
    public static List<String> desiredActiveDbValues() {
        return Arrays.stream(values())
                .filter(PluginInstallStatus::desiredActive)
                .map(PluginInstallStatus::dbValue)
                .toList();
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PluginInstallStatus fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
