package io.github.sudoitir.rahdar.feature.transfer;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The seam between a batch's commit on the target and its acknowledgement on the source: the one
 * moment an interruption could double a message, which the duplicate id must absorb (ADR-0097). A
 * no-op here; a test throws from it to stand in for Rahdar stopping at that moment.
 */
@Component
public class TransferFaults {

    public void afterTargetCommit(UUID runId) {
        // Nothing to do outside a test.
    }
}
