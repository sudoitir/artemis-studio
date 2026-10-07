package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalUnavailableException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Calls the approval provider on a virtual thread, bounded by {@code gate.decide-timeout} (ADR-0179). A provider
 * that throws, answers nothing or runs out of time is unavailable, and the caller fails closed. The provider's own
 * error is logged, never shown: it may say more about its policies than the requester should learn.
 */
@Component
@Slf4j
class ProviderCalls {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final GateBounds bounds;

    ProviderCalls(GateBounds bounds) {
        this.bounds = bounds;
    }

    <T> T call(String what, Callable<T> call) {
        Duration timeout = bounds.decideTimeout();
        Future<T> future = executor.submit(call);
        try {
            T answer = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (answer == null) {
                throw new ApprovalUnavailableException("The approval provider gave no answer to " + what + ".");
            }
            return answer;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("approval-provider call={} outcome=timeout after={}", what, timeout);
            throw new ApprovalUnavailableException(
                    "The approval provider did not answer within " + timeout.toSeconds() + " s; nothing was run.");
        } catch (ExecutionException e) {
            log.warn("approval-provider call={} outcome=error", what, e.getCause());
            throw new ApprovalUnavailableException("The approval provider failed to answer; nothing was run.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ApprovalUnavailableException("Interrupted while waiting for the approval provider.");
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
