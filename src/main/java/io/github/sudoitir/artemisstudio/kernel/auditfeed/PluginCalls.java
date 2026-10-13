package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
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

    /** Runs {@code call} in the plugin; throws what it threw, or a {@link TimeoutException} after {@code timeout}. */
    <T> T call(PluginHandle handle, Callable<T> call, Duration timeout) throws Exception {
        Future<T> running = executor.submit(() -> handle.runInPlugin(call));
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

    /** Runs a task of this module's own on a virtual thread. */
    Future<?> run(Runnable task) {
        return executor.submit(task);
    }
}
