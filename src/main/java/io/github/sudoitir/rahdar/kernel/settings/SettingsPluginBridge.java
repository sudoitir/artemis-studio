package io.github.sudoitir.rahdar.kernel.settings;

import io.github.sudoitir.rahdar.kernel.plugin.PluginBridge;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Registers and deregisters a plugin's own {@link SettingsContribution} beans with
 * {@link SettingsService} (design.md, task 6.4). A plugin declares its settings in its own
 * {@code SettingsContribution} bean exactly the way a built-in module does; this bridge is what
 * makes {@link SettingsService#addSettings} and {@link SettingsService#removeSettings}
 * fire on activation and deactivation. It is ordered ahead of every other bridge, which attach in
 * bean order and detach in reverse, so a bridge that reads a plugin's setting while attaching (a
 * job's interval, say) finds its keys already registered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class SettingsPluginBridge implements PluginBridge {

    private final SettingsService settings;

    /**
     * Which {@link PluginHandle} currently owns each plugin id's keys in {@code settings}: the
     * Instant activation class attaches a new version before the old one detaches, so
     * {@link SettingsService} briefly holds the same id's keys from both, already superseded in
     * favour of the new version's by {@link SettingsService#addSettings}. {@link #detach}
     * must not then strip the new version's keys back out.
     */
    private final Map<String, PluginHandle> owners = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        List<SettingDef> defs = handle.applicationContext().getBeansOfType(SettingsContribution.class).values().stream()
                .filter(c -> c.featureId().equals(handle.id()))
                .flatMap(c -> c.settings().stream())
                .toList();
        owners.put(handle.id(), handle);
        settings.addSettings(handle.id(), defs, SettingsPermissions.SETTINGS_WRITE);
    }

    @Override
    public void detach(PluginHandle handle) {
        if (owners.remove(handle.id(), handle)) {
            settings.removeSettings(handle.id());
        }
    }
}
