package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
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

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * Runs {@code call} in the plugin; throws what it threw, or a {@link TimeoutException} after {@code timeout}.
     * A call that ignores its interrupt keeps running, so no second call under the same {@code key} starts
     * until it has really returned: it throws {@link Busy} instead.
     */
    <T> T call(String key, PluginHandle handle, Callable<T> call, Duration timeout) throws Exception {
        if (!running.add(key)) {
            throw new Busy(key);
        }
        Future<T> running = submit(key, handle, call);
        try {
            return running.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
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
                    this.running.remove(key);
                }
            });
        } catch (RuntimeException e) {
            this.running.remove(key);
            throw e;
        }
    }

    /** Runs a task of this module's own on a virtual thread. */
    Future<?> run(Runnable task) {
        return executor.submit(task);
    }
}
