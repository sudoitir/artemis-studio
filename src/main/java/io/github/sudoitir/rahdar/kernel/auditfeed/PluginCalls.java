package io.github.sudoitir.rahdar.kernel.auditfeed;

import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Calls into a plugin on a virtual thread, so that a plugin that does not answer costs a timeout and nothing else. */
@Component
class PluginCalls {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    /** Thrown instead of a second call while an earlier one under the same key is still running. */
    static final class Busy extends Exception {

        Busy(String key) {
            super("The earlier call %s has not returned yet".formatted(key));
        }
    }

    /** A checked failure of the plugin's own call, which {@link PluginHandle#runInPlugin} reports as {@link Exception}. */
    static final class PluginCallFailed extends RuntimeException {

        PluginCallFailed(Throwable cause) {
            super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        }
    }

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Runs {@code call} in the plugin; throws what it threw, or a {@link TimeoutException} after {@code timeout}.
     * A call that ignores its interrupt keeps running, so no second call under the same {@code key} starts
     * until it has really returned: it throws {@link Busy} instead.
     */
    <T> T call(String key, PluginHandle handle, Callable<T> call, Duration timeout)
            throws Busy, TimeoutException, InterruptedException {
        if (!inFlight.add(key)) {
            throw new Busy(key);
        }
        Future<T> running = submit(key, handle, call);
        try {
            return running.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PluginCallFailed(cause);
        } catch (TimeoutException _) {
            running.cancel(true);
            throw new TimeoutException("Plugin \"%s\" did not answer within %s".formatted(handle.id(), timeout));
        } catch (InterruptedException e) {
            running.cancel(true);
            throw e;
        }
    }

    private <T> Future<T> submit(String key, PluginHandle handle, Callable<T> call) {
        try {
            return executor.submit(() -> {
                try {
                    return handle.runInPlugin(call);
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(key);
            throw e;
        }
    }

    /** Runs a task of this module's own on a virtual thread. */
    Future<Void> run(Runnable task) {
        return executor.submit(task, null);
    }
}
