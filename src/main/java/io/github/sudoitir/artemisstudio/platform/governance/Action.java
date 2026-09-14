package io.github.sudoitir.artemisstudio.platform.governance;

/** How a sensitive value is emitted. */
public enum Action {
    /** Replaced by a marker; never shown or stored in any form. */
    DROP,
    /** Only a short suffix stays visible. */
    PARTIAL,
    /** The whole value is replaced by a marker naming its class. */
    REDACT,
    /** Left as it is — a dismissed finding's exception. */
    CLEAR
}
