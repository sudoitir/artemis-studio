package com.example.qaapprovals;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.Approver;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.GateDecision;
import io.github.sudoitir.artemisstudio.kernel.gate.GateRequest;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import io.github.sudoitir.artemisstudio.kernel.gate.PolicyRef;
import io.github.sudoitir.artemisstudio.kernel.gate.RunCheck;
import io.github.sudoitir.artemisstudio.kernel.gate.Vote;
import io.github.sudoitir.artemisstudio.kernel.gate.VoteCheck;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Holds every gated operation of anyone but the built-in administrator, who approves them: the sweep's
 * requester sees each held screen, and the administrator each decision. A request whose summary names the
 * {@code held-5} queue holds for the shortest time Studio allows, so the sweep can reach EXPIRED.
 */
@Component
public class HoldEverything implements ApprovalProvider {

    private static final PolicyRef POLICY = new PolicyRef("four-eyes", "1", "Four eyes on every change");

    @Override
    public GateDecision decide(GateRequest request) {
        if ("admin".equals(request.requester().username())) {
            return new GateDecision.Allow(POLICY);
        }
        boolean expiring = request.summary() != null && request.summary().contains("held-5");
        return new GateDecision.Hold(
                POLICY, expiring ? Duration.ofMinutes(1) : Duration.ofDays(1), "an administrator");
    }

    @Override
    public VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote) {
        return VoteCheck.allow();
    }

    @Override
    public RunCheck checkRun(HeldOperationView held, Effect now) {
        return RunCheck.allow();
    }
}
