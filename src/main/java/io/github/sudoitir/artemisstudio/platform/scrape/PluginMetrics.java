package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Plugin metrics (ADR-0113): registers each plugin's {@link PluginMetricSource} beans on attach,
 * samples them after every tier-B scrape into {@code metric_sample} and a Prometheus
 * {@link MultiGauge}, and keeps the latest values for alert evaluation, which runs after this
 * listener for the same tick.
 */
@Component
@Slf4j
public class PluginMetrics implements PluginBridge {

    /** A declared metric of a running plugin. */
    public record Declared(String plugin, PluginDescriptor.Metric metric) {}

    private record Attached(PluginHandle handle, List<PluginMetricSource> sources) {}

    private record Key(UUID clusterId, String metric) {}

    static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration WARN_EVERY = Duration.ofMinutes(1);

    private final MetricSampleWriter writer;
    private final MeterRegistry registry;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Attached> attached = new ConcurrentHashMap<>();
    private final Map<Key, Map<String, Double>> latest = new ConcurrentHashMap<>();
    private final Map<String, MultiGauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Instant> warned = new ConcurrentHashMap<>();

    public PluginMetrics(MetricSampleWriter writer, MeterRegistry registry) {
        this.writer = writer;
        this.registry = registry;
    }

    @Override
    public void attach(PluginHandle handle) {
        Map<String, PluginDescriptor.Metric> declared = new HashMap<>();
        handle.descriptor().metrics().forEach(m -> declared.put(m.name(), m));
        List<PluginMetricSource> sources = new ArrayList<>();
        for (PluginMetricSource source :
                handle.beansOfType(PluginMetricSource.class).values()) {
            String metric = call(handle, source::metric);
            if (!declared.containsKey(metric)) {
                throw new IllegalStateException(
                        "Metric source for \"%s\" names a metric plugin.json does not declare".formatted(metric));
            }
            sources.add(source);
        }
        attached.put(handle.id(), new Attached(handle, List.copyOf(sources)));
    }

    @Override
    public void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current == null || current.handle() != handle || !attached.remove(handle.id(), current)) {
            return;
        }
        for (PluginDescriptor.Metric metric : handle.descriptor().metrics()) {
            latest.keySet().removeIf(k -> k.metric().equals(metric.name()));
            MultiGauge gauge = gauges.get(metric.name());
            if (gauge != null) {
                gauge.register(List.of(), true);
            }
        }
    }

    /** The declared metrics of every running plugin. */
    public List<Declared> declared() {
        List<Declared> out = new ArrayList<>();
        attached.forEach((id, a) -> a.handle().descriptor().metrics().forEach(m -> out.add(new Declared(id, m))));
        return out;
    }

    public Optional<Declared> declared(String metric) {
        return declared().stream().filter(d -> d.metric().name().equals(metric)).findFirst();
    }

    /** The values sampled on the last tier-B scrape of a cluster, by subject; empty when none. */
    public Map<String, Double> latest(UUID clusterId, String metric) {
        return latest.getOrDefault(new Key(clusterId, metric), Map.of());
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onTierCompleted(ScrapeTierCompleted event) {
        if (event.tier() == ScrapeTierCompleted.Tier.B) {
            sample(event.clusterId());
        }
    }

    void sample(UUID clusterId) {
        for (Attached a : attached.values()) {
            for (PluginMetricSource source : a.sources()) {
                String metric = null;
                try {
                    metric = call(a.handle(), source::metric);
                    Map<String, Double> values = new HashMap<>();
                    Map<String, Double> sampled = call(a.handle(), () -> source.sample(clusterId));
                    if (sampled != null) {
                        sampled.forEach((subject, value) -> {
                            if (subject != null && value != null && Double.isFinite(value)) {
                                values.put(subject, value);
                            }
                        });
                    }
                    writer.appendPluginSamples(clusterId, metric, values);
                    latest.put(new Key(clusterId, metric), Map.copyOf(values));
                    export(a.handle().id(), metric);
                } catch (RuntimeException e) {
                    warn(a.handle().id() + "/" + metric, e);
                }
            }
        }
    }

    /** Replaces the metric's Prometheus rows with the latest values of every cluster. */
    private void export(String plugin, String metric) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        latest.forEach((key, values) -> {
            if (key.metric().equals(metric)) {
                values.forEach((subject, value) -> rows.add(
                        MultiGauge.Row.of(Tags.of("cluster", key.clusterId().toString(), "subject", subject), value)));
            }
        });
        gauges.computeIfAbsent(
                        metric,
                        m -> MultiGauge.builder("studio.plugin.metric")
                                .description("A metric a plugin publishes (ADR-0113)")
                                .tags("plugin", plugin, "metric", m)
                                .register(registry))
                .register(rows, true);
    }

    /** Calls into the plugin on a virtual thread, abandoning it after {@link #TIMEOUT}. */
    private <T> T call(PluginHandle handle, java.util.concurrent.Callable<T> body) {
        Future<T> future = calls.submit(() -> handle.runInPlugin(body));
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            future.cancel(true);
            throw new IllegalStateException("did not answer within " + TIMEOUT.toSeconds() + " s");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(String.valueOf(e.getCause()), e.getCause());
        }
    }

    private void warn(String source, RuntimeException e) {
        Instant now = Instant.now();
        Instant last = warned.get(source);
        if (last == null || last.plus(WARN_EVERY).isBefore(now)) {
            warned.put(source, now);
            log.warn("Plugin metric source {} was skipped: {}", source, e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        calls.shutdownNow();
    }
}
