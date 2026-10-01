package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Operator-started work that outlives its request, such as a bulk run or a message transfer
 * (ADR-0093 D4, extracted when the transfer became its second user). Each run is on a virtual
 * thread of its own, as the operator who started it, and can be asked to stop.
 *
 * <p>The stop flags are in-process, and so is the run: a stop asked of another replica travels as a
 * {@link ReplicaSignal} that every replica hears, and the one executing the run acts on it (ADR-0152).
 * What a run whose replica is gone becomes is each feature's own recovery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackgroundRuns {

    private static final String STOP_SIGNAL = "run-stop";

    /** After a shutdown stop, how long the runs get to record how far they got. */
    private static final Duration AFTER_STOP = Duration.ofSeconds(8);

    /** Why a run was asked to stop. */
    private enum Stop {
        OPERATOR,
        SHUTDOWN
    }

    private final OperatorHandoff handoff;
    private final StudioBus bus;

    private final Map<UUID, AtomicReference<Stop>> active = new ConcurrentHashMap<>();

    /**
     * Start {@code work} on a virtual thread, as {@code operator}. The run is active from this call
     * until {@code work} returns or throws.
     *
     * @throws IllegalStateException when a run with this id is already active in this process
     */
    public void start(UUID runId, Operator operator, Runnable work) {
        if (active.putIfAbsent(runId, new AtomicReference<>()) != null) {
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
        AtomicReference<Stop> flag = active.get(runId);
        if (flag == null) {
            return false;
        }
        flag.compareAndSet(null, Stop.OPERATOR);
        return true;
    }

    /** Tell every replica to stop this run; the one executing it acts on it. Sent with the caller's transaction. */
    public void signalStop(UUID runId) {
        bus.publish(new ReplicaSignal(STOP_SIGNAL, runId.toString()));
    }

    @EventListener
    void onSignal(ReplicaSignal signal) {
        if (STOP_SIGNAL.equals(signal.kind())) {
            requestStop(UUID.fromString(signal.key()));
        }
    }

    /** Whether a stop has been asked for this run. False once the run has ended. */
    public boolean stopRequested(UUID runId) {
        AtomicReference<Stop> flag = active.get(runId);
        return flag != null && flag.get() != null;
    }

    /** Whether this replica's shutdown is what asked this run to stop, so it is interrupted, not stopped. */
    public boolean stoppedForShutdown(UUID runId) {
        AtomicReference<Stop> flag = active.get(runId);
        return flag != null && flag.get() == Stop.SHUTDOWN;
    }

    public boolean isActive(UUID runId) {
        return active.containsKey(runId);
    }

    /**
     * Shutdown: wait up to {@code grace} for the runs to finish, then ask each one still executing to
     * stop because Studio is stopping, and give it a short while to record its progress.
     */
    public void stopForShutdown(Duration grace) {
        if (awaitIdle(grace)) {
            return;
        }
        log.warn("{} runs are still executing after {}; stopping them", active.size(), grace);
        active.values().forEach(flag -> flag.set(Stop.SHUTDOWN));
        awaitIdle(AFTER_STOP);
    }

    private boolean awaitIdle(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        try {
            while (!active.isEmpty()) {
                if (System.nanoTime() - deadline >= 0) {
                    return false;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }
}
