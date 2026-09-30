package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import org.junit.jupiter.api.Test;

class LifecycleSettingsTest {

    @Test
    void aPluginStoreNeverSharesANamespaceWithACoreStore() {
        ManagedStore store = mock(ManagedStore.class);
        RegisteredStore core = new RegisteredStore("audit", RegisteredStore.CORE, store, null);
        RegisteredStore plugin = new RegisteredStore("audit.log", "audit", store, mock(PluginHandle.class));

        assertThat(LifecycleSettings.key(core, LifecycleSettings.RETENTION)).isEqualTo("lifecycle.audit.retention");
        assertThat(LifecycleSettings.key(plugin, LifecycleSettings.RETENTION))
                .isEqualTo("lifecycle.plugin.audit.log.retention")
                .doesNotStartWith(LifecycleSettings.namespace(core) + ".");
    }
}
