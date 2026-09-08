package io.github.sudoitir.artemisstudio.persist;

/** How a subscription fills the message index (ADR-0062). */
public enum CaptureMode {
    /**
     * ADR-0060's browse poller. Complete only for messages that sit still long enough
     * to be seen by a poll, which the console states on every sampled tail.
     */
    SAMPLE,

    /**
     * A non-exclusive divert into a ring-bounded Studio queue, drained by a Core
     * consumer. Complete for what the address routed, address-scoped, per node.
     */
    CAPTURE
}
