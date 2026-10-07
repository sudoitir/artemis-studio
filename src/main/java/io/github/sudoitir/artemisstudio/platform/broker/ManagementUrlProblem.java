package io.github.sudoitir.artemisstudio.platform.broker;

/** Why a node has no management URL (ADR-0175): the classified result of the attempt to derive one. */
public enum ManagementUrlProblem {
    /** The cluster has no management URL pattern to derive from. */
    NO_PATTERN,
    /** Nothing answered at the derived address. */
    UNREACHABLE,
    /** The derived address answered and refused the management account. */
    CREDENTIALS_REJECTED,
    /** The derived address answered, but not as a Jolokia agent with an Artemis broker. */
    WRONG_ENDPOINT,
    /** The TLS handshake with the derived address failed. */
    TLS_FAILED,
    /** The derived address answered for a different broker than the node it was derived for. */
    OTHER_BROKER
}
