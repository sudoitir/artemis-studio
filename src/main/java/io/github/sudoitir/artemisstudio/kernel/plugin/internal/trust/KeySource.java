package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

/** Who pinned a trusted key: an administrator in the UI or API, or the configuration at startup. */
public enum KeySource {
    ADMIN,
    CONFIGURATION
}
