package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Registers and deregisters a plugin's own {@link SettingsContribution} beans with
 * {@link SettingsService} (design.md, task 6.4). A plugin declares its settings in its own
 * {@code SettingsContribution} bean exactly the way a built-in module does; this bridge is what
 * makes {@link SettingsService#addPluginSettings} and {@link SettingsService#removePluginSettings}
 * fire on activation and deactivation.
 */
@Component
@RequiredArgsConstructor
class SettingsPluginBridge implements PluginBridge {

    private final SettingsService settings;

    /**
     * Which {@link PluginHandle} currently owns each plugin id's keys in {@code settings}: the
     * Instant activation class attaches a new version before the old one detaches, so
     * {@link SettingsService} briefly holds the same id's keys from both, already superseded in
     * favour of the new version's by {@link SettingsService#addPluginSettings}. {@link #detach}
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
        settings.addPluginSettings(handle.id(), defs);
    }

    @Override
    public void detach(PluginHandle handle) {
        if (owners.remove(handle.id(), handle)) {
            settings.removePluginSettings(handle.id());
        }
    }
}
