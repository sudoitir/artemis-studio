package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every store under the lifecycle and its policy (ADR-0132). Core stores are the enabled modules'
 * {@link HousekeepingContributor} beans; a plugin's come and go with it. Each store's policy is
 * three settings registered under {@code lifecycle.<store>}, written with {@code data:write}.
 */
@Component
public class LifecycleRegistry {

    private final SettingsService settings;

    /** Insertion-ordered, copy-on-write: the order the Data page lists them. */
    private volatile Map<String, RegisteredStore> stores = Map.of();

    public LifecycleRegistry(SettingsService settings, List<HousekeepingContributor> contributors) {
        this.settings = settings;
        List<RegisteredStore> core = new ArrayList<>();
        for (HousekeepingContributor contributor : contributors) {
            for (ManagedStore store : contributor.stores()) {
                core.add(new RegisteredStore(store.def().id(), RegisteredStore.CORE, store, null));
            }
        }
        add(core);
    }

    public List<RegisteredStore> all() {
        return List.copyOf(stores.values());
    }

    public RegisteredStore require(String id) {
        RegisteredStore store = stores.get(id);
        if (store == null) {
            throw new NoSuchElementException("No store '" + id + "'");
        }
        return store;
    }

    /** The store's retention, or empty when it keeps everything. */
    public Optional<Duration> retention(String id) {
        String value = settings.value(LifecycleSettings.key(id, LifecycleSettings.RETENTION));
        return SettingDef.FOREVER.equalsIgnoreCase(value.trim())
                ? Optional.empty()
                : Optional.of(settings.duration(LifecycleSettings.key(id, LifecycleSettings.RETENTION)));
    }

    /** The raw retention value, as an operator set it: {@code 7d}, {@code forever}. */
    public String retentionValue(String id) {
        return settings.value(LifecycleSettings.key(id, LifecycleSettings.RETENTION));
    }

    /** The store's quota in its own unit (MiB or thousand rows); 0 means none. */
    public int quota(String id) {
        return settings.intValue(LifecycleSettings.key(id, LifecycleSettings.QUOTA));
    }

    public int quotaWarnPercent(String id) {
        return settings.intValue(LifecycleSettings.key(id, LifecycleSettings.QUOTA_WARN_PERCENT));
    }

    /** Adds a plugin's stores, replacing any it registered before (a new version attaches before the old detaches). */
    public synchronized void addPlugin(PluginHandle plugin, List<HousekeepingContributor> contributors) {
        removePlugin(plugin.id());
        List<RegisteredStore> added = new ArrayList<>();
        for (HousekeepingContributor contributor : contributors) {
            for (ManagedStore store : contributor.stores()) {
                added.add(new RegisteredStore(plugin.id() + "." + store.def().id(), plugin.id(), store, plugin));
            }
        }
        add(added);
        settings.applyRuntime();
    }

    public synchronized void removePlugin(String pluginId) {
        Map<String, RegisteredStore> next = new LinkedHashMap<>(stores);
        next.values().removeIf(s -> s.source().equals(pluginId));
        stores = java.util.Collections.unmodifiableMap(next);
        settings.removeSettings(LifecycleSettings.namespace(pluginId));
    }

    private synchronized void add(List<RegisteredStore> added) {
        Map<String, RegisteredStore> next = new LinkedHashMap<>(stores);
        for (RegisteredStore store : added) {
            if (next.putIfAbsent(store.id(), store) != null) {
                throw new IllegalStateException("Store '" + store.id() + "' is contributed twice");
            }
        }
        for (RegisteredStore store : added) {
            settings.addSettings(
                    LifecycleSettings.namespace(store.id()),
                    LifecycleSettings.policy(store.id(), store.def()),
                    DataPermissions.DATA_WRITE);
        }
        stores = java.util.Collections.unmodifiableMap(next);
    }
}
