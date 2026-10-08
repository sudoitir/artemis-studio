package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Decides whether a gated operation may run now (ADR-0179). A plugin declares {@code approvalProvider}
 * in {@code plugin.json}, naming the permission its approvers need, and exposes one bean of this
 * type; at most one provider is active. Every method must be pure and return quickly: Studio bounds
 * each call (3 seconds by default) and treats a throw or a timeout as "unavailable", which fails
 * closed. Studio owns held operations, applies its own approver rules before {@link #checkVote}
 * (ADR-0181), and enforces {@link Effect#stateKey()} itself before {@link #checkRun}.
 */
@PluginApi
public interface ApprovalProvider {

    /** Allow, hold or deny. In {@link GateRequest.Mode#PREVIEW} nothing is stored either way. */
    GateDecision decide(GateRequest request);

    /** Whether this approver may cast this vote on this request, once Studio's own rules passed. */
    VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote);

    /**
     * Whether an approved request may still run, given its effect now; for example refusing when the
     * count grew beyond what the policy tolerates.
     */
    RunCheck checkRun(HeldOperationView held, Effect now);
}
