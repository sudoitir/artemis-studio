package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adds a plugin's declared stream topics, and any {@link EventReplay} bean its own context
 * defines, to {@link StreamTopicRegistry} on activation, and removes them on deactivation
 * (design.md, task 6.4).
 */
@Component
@RequiredArgsConstructor
class StreamPluginBridge implements PluginBridge {

    private final StreamTopicRegistry topics;

    /**
     * Which {@link PluginHandle} currently owns each plugin id's registration in {@link #topics}:
     * the Instant activation class attaches a new version before the old one detaches, so both
     * briefly hold the same id in {@code topics}, and {@link #detach} must not remove the newer
     * version's topics.
     */
    private final Map<String, PluginHandle> owners = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        List<String> topicNames = handle.descriptor().streamTopics();
        List<EventReplay> replays = List.copyOf(
                handle.applicationContext().getBeansOfType(EventReplay.class).values());
        owners.put(handle.id(), handle);
        topics.addPlugin(handle.id(), topicNames, replays);
    }

    @Override
    public void detach(PluginHandle handle) {
        if (owners.remove(handle.id(), handle)) {
            topics.removePlugin(handle.id());
        }
    }
}
