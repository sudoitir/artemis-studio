package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginBridge;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Every {@link GatedOperation} the gate can ask about: Rahdar's own beans, and those of the plugins
 * attached on this replica. The engine finds an operation by the type of its parameters ({@link
 * #forParams}) or by its name ({@link #forType}). A plugin's types must start with {@code
 * <pluginId>:} and Rahdar's must not contain a colon, and no two operations share a type or a
 * parameter type; a plugin that breaks a rule fails its activation, and Rahdar's own beans fail the
 * boot. A plugin's operations run inside the plugin, with its class loader and counted in flight.
 *
 * <p>Rahdar's own operations are read once every singleton exists, not when this registry is built: a gated
 * service needs the gate, and an operation's {@link GatedOperation#replay} needs the service, so reading them
 * any earlier would be a dependency cycle.
 */
@Component
public class GatedOperationRegistry implements PluginBridge, SmartInitializingSingleton {

    private record Attached(PluginHandle handle, List<GatedOperation<?>> operations) {}

    private record Index(Map<String, GatedOperation<?>> byType, Map<Class<?>, GatedOperation<?>> byParams) {}

    private final ObjectProvider<GatedOperation<?>> rahdarBeans;
    private final Map<String, Attached> attached = new HashMap<>();
    private List<GatedOperation<?>> rahdarOperations;
    private final AtomicReference<Index> index = new AtomicReference<>();

    public GatedOperationRegistry(ObjectProvider<GatedOperation<?>> rahdarOperations) {
        this.rahdarBeans = rahdarOperations;
    }

    @Override
    public void afterSingletonsInstantiated() {
        index();
    }

    public Optional<GatedOperation<?>> forType(String type) {
        return Optional.ofNullable(index().byType().get(type));
    }

    /** The operation whose parameters are {@code paramsType}. */
    @SuppressWarnings("unchecked")
    public <P extends Record> Optional<GatedOperation<P>> forParams(Class<P> paramsType) {
        return Optional.ofNullable((GatedOperation<P>) index().byParams().get(paramsType));
    }

    public Collection<GatedOperation<?>> all() {
        return index().byType().values();
    }

    private Index index() {
        Index current = index.get();
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index.get() == null) {
                index.set(indexOf(rahdar(), attached));
            }
            return index.get();
        }
    }

    /** Rahdar's own operations, read and checked the first time they are needed. */
    private synchronized List<GatedOperation<?>> rahdar() {
        if (rahdarOperations == null) {
            List<GatedOperation<?>> operations = rahdarBeans.orderedStream().toList();
            for (GatedOperation<?> operation : operations) {
                if (operation.type().contains(":")) {
                    throw new IllegalStateException(
                            "Gated operation \"%s\" must not contain a colon: only plugins use <pluginId>:<name>"
                                    .formatted(operation.type()));
                }
            }
            rahdarOperations = operations;
        }
        return rahdarOperations;
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
        index.set(indexOf(rahdar(), next));
        attached.put(handle.id(), next.get(handle.id()));
    }

    @Override
    public synchronized void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current != null && current.handle() == handle) {
            attached.remove(handle.id());
            index.set(indexOf(rahdar(), attached));
        }
    }

    private static Index indexOf(List<GatedOperation<?>> rahdar, Map<String, Attached> plugins) {
        Map<String, GatedOperation<?>> byType = new HashMap<>();
        Map<Class<?>, GatedOperation<?>> byParams = new HashMap<>();
        List<GatedOperation<?>> all = new ArrayList<>(rahdar);
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
