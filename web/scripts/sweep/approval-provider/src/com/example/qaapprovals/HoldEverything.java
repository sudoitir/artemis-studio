package com.example.qaapprovals;

import io.github.sudoitir.rahdar.kernel.gate.ApprovalProvider;
import io.github.sudoitir.rahdar.kernel.gate.Approver;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.GateRequest;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperationView;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.gate.RunCheck;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.github.sudoitir.rahdar.kernel.gate.VoteCheck;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Holds every gated operation of anyone but the built-in administrator, who approves them: the sweep's
 * requester sees each held screen, and the administrator each decision. A request whose summary names the
 * {@code held-5} queue holds for the shortest time Rahdar allows, so the sweep can reach EXPIRED.
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
