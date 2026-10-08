package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.GatePreview;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import java.util.function.Supplier;

/**
 * The {@link OperationGate} as one plugin sees it: the shared engine, but only for the operation types the plugin
 * registered itself ({@code <pluginId>:<name>}). A plugin can gate its own work and cannot run or preview one of
 * Studio's types or another plugin's. Bound to the plugin's id by the host, so no call can name another plugin.
 */
final class ScopedOperationGate implements OperationGate {

    private final OperationGate engine;
    private final GatedOperationRegistry operations;
    private final String prefix;

    ScopedOperationGate(OperationGate engine, GatedOperationRegistry operations, String pluginId) {
        this.engine = engine;
        this.operations = operations;
        this.prefix = pluginId + ":";
    }

    @Override
    public <R> R run(Operation operation, Supplier<R> action) {
        requireOwn(operation);
        return engine.run(operation, action);
    }

    @Override
    public GatePreview preview(Operation operation) {
        requireOwn(operation);
        return engine.preview(operation);
    }

    /** A parameter type nobody registered is left to the engine, which refuses it. */
    private void requireOwn(Operation operation) {
        operations.forParams(operation.params().getClass()).ifPresent(type -> {
            if (!type.type().startsWith(prefix)) {
                throw new IllegalArgumentException("A plugin may gate only its own operations (\"" + prefix
                        + "<name>\"), not \"" + type.type() + "\".");
            }
        });
    }
}
