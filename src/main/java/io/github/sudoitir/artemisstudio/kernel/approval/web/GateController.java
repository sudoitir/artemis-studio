package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.approval.Approvals;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The approval gate's state for every signed-in user, so the console can show its banner. */
@RestController
@RequestMapping("/gate")
@RequiredArgsConstructor
public class GateController {

    private final Approvals approvals;

    /** Whether approvals are on, by which provider, whether it runs here, and whether break-glass is set. */
    @GetMapping("/status")
    public HeldOperationViews.GateStatusView status() {
        Approvals.Status status = approvals.status();
        return new HeldOperationViews.GateStatusView(
                status.armed(), status.providerId(), status.attached(), status.breakGlass());
    }
}
