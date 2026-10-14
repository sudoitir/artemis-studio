package io.github.sudoitir.rahdar.platform.broker;

/**
 * What the application gives a {@link JolokiaBrokerClient} beyond its URL: where the broker's clock reading and each
 * call's outcome go, the per-node ceiling taken before every request, and the guard against a call inside a database
 * transaction. {@link #NONE} is for a client built outside the application, in a test or a one-off probe.
 *
 * @param clockOffsets where the broker's own clock reading goes; null to measure nothing
 * @param callHealth where each call's outcome goes, for broker health; null to record nothing
 * @param limiter the per-node ceiling (ADR-0076); null to take no permit
 * @param guard refuses a request made inside a database transaction where the application switches that on
 */
public record JolokiaClientHooks(
        ClockOffsetRegistry clockOffsets, NodeCallHealth callHealth, NodeCallLimiter limiter, BrokerCallGuard guard) {

    public static final JolokiaClientHooks NONE = new JolokiaClientHooks(null, null, null, BrokerCallGuard.OFF);
}
