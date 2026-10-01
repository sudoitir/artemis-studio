package io.github.sudoitir.artemisstudio.kernel.core;

/**
 * The order Studio releases broker resources in (operational-health spec). Spring stops
 * lifecycle beans from the highest phase down, so each step here runs before the next:
 *
 * <ol>
 *   <li>{@link #DRAIN} — report not ready and keep serving while the load balancer notices, before
 *       anything is stopped and before the web server's own graceful shutdown (phase
 *       {@code MAX_VALUE - 1024}), which refuses new connections;
 *   <li>{@link #PLUGINS} — close every running plugin, before the web server finishes its
 *       graceful shutdown and before stream/jobs shut down (design.md §2, task 6.8);
 *   <li>{@link #STREAM} — stop accepting and delivering stream events;
 *   <li>{@link #RUNS} — let in-flight bulk runs and transfers finish, or record them interrupted;
 *   <li>{@link #JOBS} — no scheduled job starts again, and a running one is given a bounded wait;
 *   <li>{@link #BUFFERS} — write records still buffered in memory, while the database and broker are up;
 *   <li>{@link #BROKER_CALLS} — stop scraping; no new management call starts;
 *   <li>{@link #SUBSCRIPTIONS} — close notification subscriptions and message consumers;
 *   <li>{@link #CORE_POOL} — close message-transport connections;
 *   <li>{@link #REPLICA} — record that this replica stopped.
 * </ol>
 *
 * <p>Management clients are shared beans and are shut down with the context, after these.
 */
public final class ShutdownPhases {

    public static final int DRAIN = Integer.MAX_VALUE - 250;
    public static final int PLUGINS = Integer.MAX_VALUE - 500;
    public static final int STREAM = Integer.MAX_VALUE - 1000;
    public static final int RUNS = STREAM - 250;
    public static final int JOBS = STREAM - 500;
    public static final int BUFFERS = STREAM - 750;
    public static final int BROKER_CALLS = STREAM - 1000;
    public static final int SUBSCRIPTIONS = BROKER_CALLS - 1000;
    public static final int CORE_POOL = SUBSCRIPTIONS - 1000;
    public static final int REPLICA = CORE_POOL - 1000;

    private ShutdownPhases() {}
}
