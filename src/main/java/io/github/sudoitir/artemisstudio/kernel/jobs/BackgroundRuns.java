package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operator-started work that outlives its request, such as a bulk run or a message transfer
 * (ADR-0093 D4, extracted when the transfer became its second user). Each run is on a virtual
 * thread of its own, as the operator who started it, and can be asked to stop.
 *
 * <p>The stop flags are in-process: Studio runs as one instance per deployment (ADR-0093). What a
 * run that was executing when Studio stopped becomes is each feature's own recovery.
 */
@Component
@RequiredArgsConstructor
public class BackgroundRuns {

    private final OperatorHandoff handoff;

    private final Map<UUID, AtomicBoolean> active = new ConcurrentHashMap<>();

    /**
     * Start {@code work} on a virtual thread, as {@code operator}. The run is active from this call
     * until {@code work} returns or throws.
     *
     * @throws IllegalStateException when a run with this id is already active in this process
     */
    public void start(UUID runId, Operator operator, Runnable work) {
        if (active.putIfAbsent(runId, new AtomicBoolean()) != null) {
            throw new IllegalStateException("Run " + runId + " is already executing.");
        }
        try {
            Thread.ofVirtual().name("run-" + runId).start(() -> {
                try {
                    handoff.runAs(operator, work);
                } finally {
                    active.remove(runId);
                }
            });
        } catch (RuntimeException e) {
            active.remove(runId);
            throw e;
        }
    }

    /** Ask a run to stop at its next check. False when the run is not executing in this process. */
    public boolean requestStop(UUID runId) {
        AtomicBoolean flag = active.get(runId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    /** Whether a stop has been asked for this run. False once the run has ended. */
    public boolean stopRequested(UUID runId) {
        AtomicBoolean flag = active.get(runId);
        return flag != null && flag.get();
    }

    public boolean isActive(UUID runId) {
        return active.containsKey(runId);
    }
}
