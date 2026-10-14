package io.github.sudoitir.rahdar.kernel.core;

import org.springframework.boot.health.contributor.Status;

/**
 * Rahdar's operational health vocabulary (operational-health spec). Its contributors — jobs,
 * brokers, subscriptions — form the {@code rahdar} health group, which never feeds liveness
 * or readiness: a broker outage or a stalled job must not get Rahdar restarted.
 */
public final class RahdarHealth {

    /** Working, with something an operator should look at. Ordered between DOWN and UP. */
    public static final Status DEGRADED = new Status("DEGRADED");

    private RahdarHealth() {}
}
