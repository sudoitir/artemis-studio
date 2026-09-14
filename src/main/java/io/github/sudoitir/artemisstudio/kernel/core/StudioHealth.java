package io.github.sudoitir.artemisstudio.kernel.core;

import org.springframework.boot.health.contributor.Status;

/**
 * Studio's operational health vocabulary (operational-health spec). Its contributors — jobs,
 * brokers, subscriptions — form the {@code studio} health group, which never feeds liveness
 * or readiness: a broker outage or a stalled job must not get Studio restarted.
 */
public final class StudioHealth {

    /** Working, with something an operator should look at. Ordered between DOWN and UP. */
    public static final Status DEGRADED = new Status("DEGRADED");

    private StudioHealth() {}
}
