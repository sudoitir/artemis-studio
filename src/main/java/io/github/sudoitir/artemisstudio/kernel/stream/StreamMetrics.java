package io.github.sudoitir.artemisstudio.kernel.stream;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@code studio.stream.clients}: the event streams open on this instance, across every cluster. */
@Component
@RequiredArgsConstructor
class StreamMetrics implements MeterBinder {

    private final SseHub hub;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("studio.stream.clients", hub, SseHub::clientCount)
                .description("Event stream clients connected to this instance")
                .register(registry);
    }
}
