package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperation;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScopedOperationGateTest {

    record Params(String name) {}

    private final OperationGate engine = mock(OperationGate.class);
    private final GatedOperationRegistry registry = mock(GatedOperationRegistry.class);

    @SuppressWarnings("unchecked")
    private void registered(String type) {
        GatedOperation operation = mock(GatedOperation.class);
        when(operation.type()).thenReturn(type);
        when(registry.forParams(Params.class)).thenReturn(Optional.of(operation));
    }

    @Test
    void aStudioTypeIsRefusedAndNeverReachesTheEngine() {
        registered("queue.purge");
        OperationGate gate = new ScopedOperationGate(engine, registry, "acme");
        Operation operation = Operation.of(new Params("a"));

        assertThatThrownBy(() -> gate.run(operation, () -> "ran"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue.purge");
        assertThatThrownBy(() -> gate.preview(operation)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engine);
    }

    @Test
    void anotherPluginsTypeIsRefused() {
        registered("other:thing");
        OperationGate gate = new ScopedOperationGate(engine, registry, "acme");
        Operation operation = Operation.of(new Params("a"));

        assertThatThrownBy(() -> gate.run(operation, () -> "ran")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engine);
    }

    @Test
    void theOwnTypeIsHandedToTheEngine() {
        registered("acme:thing");
        Operation operation = Operation.of(new Params("a"));
        when(engine.run(org.mockito.ArgumentMatchers.eq(operation), org.mockito.ArgumentMatchers.any()))
                .thenReturn("held");

        assertThat(new ScopedOperationGate(engine, registry, "acme").run(operation, () -> "ran"))
                .isEqualTo("held");
    }
}
