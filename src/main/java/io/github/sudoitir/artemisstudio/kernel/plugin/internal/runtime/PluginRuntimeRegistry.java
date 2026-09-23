package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * What {@link io.github.sudoitir.artemisstudio.kernel.plugin.web.PluginGateway} forwards against:
 * one {@link AtomicReference} per plugin id, so a version swap or a status change is a single
 * reference set (design.md §3, task 6.3) that a concurrent in-flight request always sees either
 * the old or the new value of, never a torn one.
 */
@Component
public class PluginRuntimeRegistry {

    /** What a plugin id currently resolves to. */
    public sealed interface Slot permits Active, Updating, Failed {}

    public record Active(PluginRuntime runtime) implements Slot {}

    /** In a Brief-maintenance window: the gateway answers 503 with a retry hint. */
    public record Updating(int retryAfterSeconds) implements Slot {}

    /** The plugin's last activation failed and it is not serving traffic. */
    public record Failed(String reason) implements Slot {}

    private final Map<String, AtomicReference<Slot>> slots = new ConcurrentHashMap<>();

    /** Absent means the plugin is not installed, or disabled — the gateway answers 404. */
    public Optional<Slot> get(String pluginId) {
        AtomicReference<Slot> ref = slots.get(pluginId);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.get());
    }

    /** Sets (or replaces) the slot for a plugin id — the one-reference-set version swap. */
    public void set(String pluginId, Slot slot) {
        slots.computeIfAbsent(pluginId, id -> new AtomicReference<>()).set(slot);
    }

    /** The plugin is uninstalled or disabled: the gateway goes back to answering 404. */
    public void remove(String pluginId) {
        slots.remove(pluginId);
    }

    /** Ids whose slot is currently {@link Active} — the host uses this to enumerate every running
     * plugin to close at shutdown (design.md §2, task 6.8). */
    public java.util.Set<String> activeIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        slots.forEach((id, ref) -> {
            if (ref.get() instanceof Active) {
                ids.add(id);
            }
        });
        return ids;
    }
}
