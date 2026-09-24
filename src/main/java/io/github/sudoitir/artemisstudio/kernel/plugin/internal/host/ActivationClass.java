package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

/** design.md §5: what confirming an activation will actually do, and for how long. */
public enum ActivationClass {
    /** New version warms up alongside the old one; a single reference swap, no downtime. */
    INSTANT,
    /** The gateway answers 503 for this plugin while its schema migrates and it restarts. */
    BRIEF_MAINTENANCE,
    /** {@code activation: restart} was declared, or the running instance is stuck: whole Studio. */
    RESTART
}
