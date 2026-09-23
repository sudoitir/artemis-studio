package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import jakarta.persistence.EntityManagerFactory;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * One running instance of a plugin (design.md §3, task 6.2): its own classloader, its own child
 * {@link GenericWebApplicationContext} parented on the curated API context, its own
 * {@link DispatcherServlet}, and its own database pool, {@link EntityManagerFactory} and
 * transaction manager. Built by {@link PluginRuntimeFactory}; the gateway holds the currently
 * active one per plugin id and forwards every call to {@link #servlet()}.
 *
 * <p>{@link #close()} runs the order the design fixes, because each step assumes the one before it
 * already ran: exclude every in-flight call, detach bridges (so no bridge is still holding the
 * context open when it closes), close the context, close the {@link EntityManagerFactory}, close
 * the pool, close the classloader, then clear the JVM-wide caches that would otherwise keep the
 * classloader reachable after every reference this object itself held is gone.
 *
 * <p>"Exclude every in-flight call" is a {@link ReentrantReadWriteLock}, not the poll-sleep drain
 * this design started with: a caller reserves a call with {@link #beginCall()}, which holds the
 * read lock for the call's duration, and {@link #close()} sets {@link #closed} then blocks on the
 * write lock — which cannot be granted until every held read lock has been released — before
 * tearing anything down. That makes "no call is still running against a closing runtime" an actual
 * guarantee rather than a best-effort wait: the poll-sleep version could time out with calls still
 * in flight and proceed to close under them anyway, and a call that reserved itself a moment after
 * the window closed could start running against an already-torn-down context.
 */
@Slf4j
public final class PluginRuntime implements AutoCloseable {

    private final String id;
    private final PluginDescriptor descriptor;
    private final URLClassLoader classLoader;
    private final GenericWebApplicationContext context;
    private final DispatcherServlet servlet;
    private final HikariDataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;
    private final ApplicationContext mainContext;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final PluginHandle handle = new Handle();
    private final String servletContextAttribute;
    private volatile boolean closed;

    PluginRuntime(
            PluginDescriptor descriptor,
            URLClassLoader classLoader,
            GenericWebApplicationContext context,
            DispatcherServlet servlet,
            HikariDataSource dataSource,
            EntityManagerFactory entityManagerFactory,
            ApplicationContext mainContext,
            String servletName) {
        this.id = descriptor.id();
        this.descriptor = descriptor;
        this.classLoader = classLoader;
        this.context = context;
        this.servlet = servlet;
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
        this.mainContext = mainContext;
        this.servletContextAttribute =
                org.springframework.web.servlet.FrameworkServlet.SERVLET_CONTEXT_PREFIX + servletName;
    }

    public String id() {
        return id;
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public DispatcherServlet servlet() {
        return servlet;
    }

    public URLClassLoader classLoader() {
        return classLoader;
    }

    public PluginHandle handle() {
        return handle;
    }

    /**
     * Reserves one in-flight call by holding the read lock for its duration — the gateway wraps
     * every forwarded request in this. Returns {@code false} without reserving anything once
     * {@link #close()} has started, which the caller must treat as "this runtime is gone": look the
     * plugin id up again, or answer the caller with a retry.
     */
    public boolean beginCall() {
        if (closed) {
            return false;
        }
        lock.readLock().lock();
        if (closed) {
            lock.readLock().unlock();
            return false;
        }
        return true;
    }

    public void endCall() {
        lock.readLock().unlock();
    }

    /**
     * Runs {@code call} with the thread context classloader set to this plugin's own, reserved
     * as an in-flight call ({@link #beginCall()}) for the duration so {@link #close()} cannot tear
     * the plugin down while it runs.
     */
    public <T> T runInPlugin(Callable<T> call) throws Exception {
        if (!beginCall()) {
            throw new IllegalStateException("Plugin '" + id + "' is unloading");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            return call.call();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            endCall();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Blocks until every call that already holds the read lock (i.e. already reserved via
        // beginCall()) has released it via endCall(); a call that checks `closed` after this point
        // sees true and never reserves one. Teardown below only starts once that is guaranteed.
        lock.writeLock().lock();
        try {
            teardown();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void teardown() {
        for (PluginBridge bridge :
                mainContext.getBeansOfType(PluginBridge.class).values()) {
            try {
                bridge.detach(handle);
            } catch (RuntimeException e) {
                log.warn("Plugin bridge {} threw on detach for plugin '{}'; unload continues.", bridge, id, e);
            }
        }
        try {
            // FrameworkServlet.init() registers the plugin's own WebApplicationContext as an
            // attribute of the *shared* main-application ServletContext, keyed by servlet name.
            // That ServletContext outlives every plugin activation, so the attribute is a strong
            // reference nothing else ever clears — found by walking a heap dump of an unload that
            // didn't collect: the plugin's classloader was reachable only through it.
            // FrameworkServlet.destroy() does not remove it (it only closes the context, which the
            // servlet constructor's "context injected" flag then even skips, since we already close
            // it ourselves below), so this removal is on us.
            context.getServletContext().removeAttribute(servletContextAttribute);
        } catch (RuntimeException e) {
            log.warn("Plugin '{}' could not remove its ServletContext attribute; unload continues.", id, e);
        }
        try {
            servlet.destroy();
        } catch (RuntimeException e) {
            log.warn("Plugin '{}' servlet.destroy() threw; unload continues.", id, e);
        }
        try {
            context.close();
        } catch (RuntimeException e) {
            log.warn("Plugin '{}' context.close() threw; unload continues.", id, e);
        }
        try {
            entityManagerFactory.close();
        } catch (RuntimeException e) {
            log.warn("Plugin '{}' EntityManagerFactory.close() threw; unload continues.", id, e);
        }
        try {
            dataSource.close();
        } catch (RuntimeException e) {
            log.warn("Plugin '{}' pool close() threw; unload continues.", id, e);
        }
        try {
            classLoader.close();
        } catch (java.io.IOException e) {
            log.warn("Plugin '{}' classloader close() threw; unload continues.", id, e);
        }
        PluginClassloaderCaches.clear(classLoader);
    }

    private final class Handle implements PluginHandle {
        @Override
        public String id() {
            return id;
        }

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ApplicationContext applicationContext() {
            return context;
        }

        @Override
        public ClassLoader classLoader() {
            return classLoader;
        }

        @Override
        public <T> T runInPlugin(Callable<T> call) throws Exception {
            return PluginRuntime.this.runInPlugin(call);
        }
    }
}
