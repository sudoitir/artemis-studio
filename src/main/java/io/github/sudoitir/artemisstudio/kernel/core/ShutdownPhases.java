package io.github.sudoitir.artemisstudio.kernel.core;

/**
 * The order Studio releases broker resources in (operational-health spec). Spring stops
 * lifecycle beans from the highest phase down, so each step here runs before the next:
 *
 * <ol>
 *   <li>{@link #STREAM} — stop accepting and delivering stream events;
 *   <li>{@link #BROKER_CALLS} — stop background jobs and scraping; no new management call starts;
 *   <li>{@link #SUBSCRIPTIONS} — close notification subscriptions and message consumers;
 *   <li>{@link #CORE_POOL} — close message-transport connections.
 * </ol>
 *
 * <p>Management clients are built per call and hold nothing open, so nothing follows.
 */
public final class ShutdownPhases {

    public static final int STREAM = Integer.MAX_VALUE - 1000;
    public static final int BROKER_CALLS = STREAM - 1000;
    public static final int SUBSCRIPTIONS = BROKER_CALLS - 1000;
    public static final int CORE_POOL = SUBSCRIPTIONS - 1000;

    private ShutdownPhases() {}
}
