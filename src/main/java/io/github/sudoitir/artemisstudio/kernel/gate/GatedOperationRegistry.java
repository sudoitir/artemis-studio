package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every {@link GatedOperation} the gate can ask about: Studio's own beans, and those of the plugins
 * attached on this replica. The engine finds an operation by the type of its parameters ({@link
 * #forParams}) or by its name ({@link #forType}). A plugin's types must start with {@code
 * <pluginId>:} and Studio's must not contain a colon, and no two operations share a type or a
 * parameter type; a plugin that breaks a rule fails its activation, and Studio's own beans fail the
 * boot. A plugin's operations run inside the plugin, with its class loader and counted in flight.
 */
@Component
public class GatedOperationRegistry implements PluginBridge {

    private record Attached(PluginHandle handle, List<GatedOperation<?>> operations) {}

    private record Index(Map<String, GatedOperation<?>> byType, Map<Class<?>, GatedOperation<?>> byParams) {}

    private final List<GatedOperation<?>> studioOperations;
    private final Map<String, Attached> attached = new HashMap<>();
    private volatile Index index;

    public GatedOperationRegistry(ObjectProvider<GatedOperation<?>> studioOperations) {
        this.studioOperations = studioOperations.orderedStream().toList();
        for (GatedOperation<?> operation : this.studioOperations) {
            if (operation.type().contains(":")) {
                throw new IllegalStateException(
                        "Gated operation \"%s\" must not contain a colon: only plugins use <pluginId>:<name>"
                                .formatted(operation.type()));
            }
        }
        this.index = indexOf(this.studioOperations, Map.of());
    }

    public Optional<GatedOperation<?>> forType(String type) {
        return Optional.ofNullable(index.byType().get(type));
    }

    /** The operation whose parameters are {@code paramsType}. */
    @SuppressWarnings("unchecked")
    public <P extends Record> Optional<GatedOperation<P>> forParams(Class<P> paramsType) {
        return Optional.ofNullable((GatedOperation<P>) index.byParams().get(paramsType));
    }

    public Collection<GatedOperation<?>> all() {
        return index.byType().values();
    }

    @Override
    public synchronized void attach(PluginHandle handle) {
        List<GatedOperation<?>> operations = new ArrayList<>();
        String prefix = handle.id() + ":";
        for (GatedOperation<?> bean : handle.beansOfType(GatedOperation.class).values()) {
            if (!bean.type().startsWith(prefix) || bean.type().length() == prefix.length()) {
                throw new IllegalStateException("Gated operation \"%s\" of plugin \"%s\" must be named \"%s<name>\""
                        .formatted(bean.type(), handle.id(), prefix));
            }
            operations.add(bound(handle, bean));
        }
        if (operations.isEmpty()) {
            return;
        }
        Map<String, Attached> next = new HashMap<>(attached);
        next.put(handle.id(), new Attached(handle, operations));
        index = indexOf(studioOperations, next);
        attached.put(handle.id(), next.get(handle.id()));
    }

    @Override
    public synchronized void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current != null && current.handle() == handle) {
            attached.remove(handle.id());
            index = indexOf(studioOperations, attached);
        }
    }

    private static Index indexOf(List<GatedOperation<?>> studio, Map<String, Attached> plugins) {
        Map<String, GatedOperation<?>> byType = new HashMap<>();
        Map<Class<?>, GatedOperation<?>> byParams = new HashMap<>();
        List<GatedOperation<?>> all = new ArrayList<>(studio);
        plugins.values().forEach(a -> all.addAll(a.operations()));
        for (GatedOperation<?> operation : all) {
            if (byType.putIfAbsent(operation.type(), operation) != null) {
                throw new IllegalStateException("Two gated operations are named \"%s\"".formatted(operation.type()));
            }
            if (byParams.putIfAbsent(operation.paramsType(), operation) != null) {
                throw new IllegalStateException("Gated operations \"%s\" and \"%s\" share the parameters %s"
                        .formatted(
                                byParams.get(operation.paramsType()).type(),
                                operation.type(),
                                operation.paramsType().getName()));
            }
        }
        return new Index(Map.copyOf(byType), Map.copyOf(byParams));
    }

    private static <P extends Record> GatedOperation<P> bound(PluginHandle handle, GatedOperation<?> bean) {
        @SuppressWarnings("unchecked")
        GatedOperation<P> typed = (GatedOperation<P>) bean;
        return new PluginBound<>(handle, typed);
    }

    private record PluginBound<P extends Record>(PluginHandle handle, GatedOperation<P> bean)
            implements GatedOperation<P> {

        @Override
        public String type() {
            return bean.type();
        }

        @Override
        public int version() {
            return bean.version();
        }

        @Override
        public Class<P> paramsType() {
            return bean.paramsType();
        }

        @Override
        public Set<Trait> traits(P params) {
            return inPlugin(() -> bean.traits(params));
        }

        @Override
        public ExecutionMode mode() {
            return bean.mode();
        }

        @Override
        public OperationScope scope(P params) {
            return inPlugin(() -> bean.scope(params));
        }

        @Override
        public String summary(P params) {
            return inPlugin(() -> bean.summary(params));
        }

        @Override
        public List<DisplayRow> display(P params) {
            return inPlugin(() -> bean.display(params));
        }

        @Override
        public Set<String> redactedPaths() {
            return inPlugin(bean::redactedPaths);
        }

        @Override
        public Effect estimate(P params) {
            return inPlugin(() -> bean.estimate(params));
        }

        @Override
        public void replay(P params) {
            inPlugin(() -> {
                bean.replay(params);
                return null;
            });
        }

        private <T> T inPlugin(java.util.concurrent.Callable<T> call) {
            return ApprovalProviderRegistry.inPlugin(handle, call);
        }
    }
}
