package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.function.Supplier;

/**
 * The one gate every gated operation passes (ADR-0179). A gated service method authorizes as usual,
 * returns at once on a dry run, and then hands its action to {@link #run}. Never call it inside a
 * transaction.
 */
@PluginApi
public interface OperationGate {

    /**
     * Runs {@code action} when no provider is armed, when the operation is covered by an approved
     * parent, or when the provider allows it.
     *
     * @throws OperationHeldException when the provider holds it for approval
     * @throws OperationDeniedException when the provider denies it
     * @throws ApprovalUnavailableException when a provider is armed but cannot answer
     * @throws ApprovalReasonRequiredException when the policy needs a reason and none was given
     */
    <R> R run(Operation operation, Supplier<R> action);

    /** What {@link #run} would do with this operation now, without running or holding anything. */
    GatePreview preview(Operation operation);
}
