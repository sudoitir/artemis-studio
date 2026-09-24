package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * Republishes every core event whose class (or, for a plain-object event, whose payload's class)
 * carries {@link PluginApi} into each running plugin's own context (design.md, task 6.6): a plugin
 * reacts to a core delete event the same way a built-in listener does, without ever being handed a
 * reference to the publisher.
 *
 * <p>Republishing goes straight to the plugin context's own {@link ApplicationEventMulticaster}
 * rather than through {@code ApplicationContext.publishEvent}, which would also bubble the event
 * back up to this context's parent and double-deliver it to every built-in listener. A plugin
 * listener that throws is logged and isolated — set as the child multicaster's
 * {@link SimpleApplicationEventMulticaster#setErrorHandler(org.springframework.util.ErrorHandler)}
 * on attach — so one broken plugin listener never reaches this class, let alone the original
 * publisher.
 */
@Slf4j
@Component
class CoreEventPluginBridge implements PluginBridge, ApplicationListener<ApplicationEvent> {

    private final Map<String, PluginHandle> active = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        ApplicationEventMulticaster multicaster =
                handle.applicationContext().getBean(ApplicationEventMulticaster.class);
        if (multicaster instanceof SimpleApplicationEventMulticaster simple) {
            simple.setErrorHandler(
                    e -> log.warn("Plugin '{}' listener threw handling a republished core event", handle.id(), e));
        }
        active.put(handle.id(), handle);
    }

    /**
     * A no-op when {@code handle} is not the current owner of its plugin id: a newer version's
     * {@link #attach} already overwrote this entry, and that subscription must not be removed by
     * the old version's detach.
     */
    @Override
    public void detach(PluginHandle handle) {
        active.remove(handle.id(), handle);
    }

    /** Which handle currently receives republished core events for {@code pluginId}, or {@code null}. Test seam. */
    PluginHandle ownerOf(String pluginId) {
        return active.get(pluginId);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        Object payload = event instanceof PayloadApplicationEvent<?> payloadEvent ? payloadEvent.getPayload() : event;
        if (!AnnotatedElementUtils.hasAnnotation(payload.getClass(), PluginApi.class)) {
            return;
        }
        for (PluginHandle handle : active.values()) {
            try {
                handle.runInPlugin(() -> {
                    handle.applicationContext()
                            .getBean(ApplicationEventMulticaster.class)
                            .multicastEvent(new PayloadApplicationEvent<>(this, payload));
                    return null;
                });
            } catch (Exception e) {
                log.warn("Plugin '{}' failed to receive a republished core event", handle.id(), e);
            }
        }
    }
}
