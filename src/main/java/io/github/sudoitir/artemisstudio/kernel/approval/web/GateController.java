package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.approval.Approvals;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperationCatalogue;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperationInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The approval gate's state and the operation types it covers, for every signed-in user. */
@RestController
@RequestMapping("/gate")
@RequiredArgsConstructor
public class GateController {

    private final Approvals approvals;
    private final GatedOperationCatalogue catalogue;

    /** Whether approvals are on, by which provider, whether it runs here, and whether break-glass is set. */
    @GetMapping("/status")
    public HeldOperationViews.GateStatusView status() {
        Approvals.Status status = approvals.status();
        return new HeldOperationViews.GateStatusView(
                status.armed(), status.providerId(), status.attached(), status.breakGlass());
    }

    /** Every operation type the gate covers, Studio's and the plugins', so the console can name them. */
    @GetMapping("/operations")
    public List<GatedOperationInfo> operations() {
        return catalogue.list();
    }
}
