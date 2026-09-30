package io.github.sudoitir.artemisstudio.kernel.replica;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReplicaShutdownSteps {

    /**
     * The first thing shutdown does (operational-health spec): readiness fails, the replica is
     * draining, {@link DrainStarted} goes out, and the replica keeps serving for
     * {@code artemis-studio.ha.drain-delay}, longer than a load balancer needs to stop routing to it.
     * Resuming a stopped context reverses it.
     */
    @Bean
    ShutdownStep drainShutdown(ReplicaRegistry registry, ApplicationEventPublisher events, HaProperties ha) {
        return new ShutdownStep(
                "drain",
                ShutdownPhases.DRAIN,
                () -> {
                    AvailabilityChangeEvent.publish(events, registry, ReadinessState.REFUSING_TRAFFIC);
                    registry.markDraining();
                    events.publishEvent(new DrainStarted());
                    sleep(ha);
                },
                () -> {
                    registry.markReady();
                    AvailabilityChangeEvent.publish(events, registry, ReadinessState.ACCEPTING_TRAFFIC);
                });
    }

    private static void sleep(HaProperties ha) {
        try {
            Thread.sleep(ha.drainDelay());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
