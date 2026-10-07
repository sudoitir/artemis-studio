package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import java.util.function.Supplier;
import org.mockito.Answers;
import org.mockito.Mockito;

/**
 * The {@link OperationGate} of a module test: the approval engine is not one of the kernel modules a feature's own test
 * bootstraps, and with no provider armed the gate runs every action as it is, which is what this does. The engine's own
 * tests start the whole application.
 */
final class NoApprovalGate {

    private NoApprovalGate() {}

    static OperationGate operationGate() {
        return Mockito.mock(
                OperationGate.class,
                Mockito.withSettings()
                        .defaultAnswer(invocation ->
                                "run".equals(invocation.getMethod().getName())
                                        ? ((Supplier<?>) invocation.getArgument(1)).get()
                                        : Answers.RETURNS_DEFAULTS.answer(invocation)));
    }
}
