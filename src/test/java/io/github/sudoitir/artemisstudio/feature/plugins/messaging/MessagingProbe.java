package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.security.PluginSecrets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Where a fixture plugin (compiled inside the test, loaded by its own classloader) leaves what it
 * was handed, and takes its orders from: a JVM-wide rendezvous on the test classpath, which a
 * plugin's classloader delegates to. Keyed by plugin id so fixtures do not see each other.
 */
public final class MessagingProbe {

    public static final Map<String, BlockingQueue<PluginMessage>> INBOX = new ConcurrentHashMap<>();
    public static final Map<String, Disposition> ANSWER = new ConcurrentHashMap<>();
    /** When present, a delivery waits for a permit before answering: a plugin that has stopped keeping up. */
    public static final Map<String, Semaphore> GATE = new ConcurrentHashMap<>();

    public static final Map<String, PluginMessaging> MESSAGING = new ConcurrentHashMap<>();
    public static final Map<String, PluginSecrets> SECRETS = new ConcurrentHashMap<>();

    private MessagingProbe() {}

    public static Disposition received(String pluginId, PluginMessage message) {
        INBOX.computeIfAbsent(pluginId, k -> new LinkedBlockingQueue<>()).add(message);
        Semaphore gate = GATE.get(pluginId);
        if (gate != null) {
            gate.acquireUninterruptibly();
        }
        return ANSWER.getOrDefault(pluginId, Disposition.ACCEPT);
    }

    public static BlockingQueue<PluginMessage> inbox(String pluginId) {
        return INBOX.computeIfAbsent(pluginId, k -> new LinkedBlockingQueue<>());
    }

    public static void forget(String pluginId) {
        INBOX.remove(pluginId);
        ANSWER.remove(pluginId);
        Semaphore gate = GATE.remove(pluginId);
        if (gate != null) {
            gate.release(10_000);
        }
        MESSAGING.remove(pluginId);
        SECRETS.remove(pluginId);
    }
}
