package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@link FeatureRegistry#addPlugin} / {@link FeatureRegistry#removePlugin} (design.md, task 6.4). */
class FeatureRegistryPluginTest {

    private FeatureRegistry registry(AtomicReference<PluginsChanged> lastEvent) {
        return new FeatureRegistry(
                new InstalledFeatures(List.of()),
                new MockEnvironment(),
                event -> lastEvent.set((PluginsChanged) event));
    }

    private static PluginDescriptor descriptor(
            String id, List<String> permissions, List<String> settingKeys, List<String> topics, List<String> tools) {
        return new PluginDescriptor(
                1,
                id,
                id,
                "1.0.0",
                new PluginDescriptor.Vendor("Acme", null, null),
                "desc",
                "Apache-2.0",
                null,
                "com.acme." + id.replace('-', '_'),
                "com.acme.Config",
                1,
                new PluginDescriptor.Studio("2026.01.0", null),
                List.of(),
                false,
                PluginDescriptor.Activation.AUTO,
                null,
                id,
                permissions.stream()
                        .map(p -> new PluginDescriptor.Permission(p, p))
                        .toList(),
                settingKeys,
                topics,
                tools.stream()
                        .map(t -> new PluginDescriptor.McpTool(t, "read", t))
                        .toList());
    }

    @Test
    void addPluginThenRemovePluginRollsTheManifestVersionAndPublishesAnEvent() {
        AtomicReference<PluginsChanged> lastEvent = new AtomicReference<>();
        FeatureRegistry registry = registry(lastEvent);
        String before = registry.manifestVersion();

        registry.addPlugin(descriptor(
                "acme-notes",
                List.of("acme-notes:read"),
                List.of("acme-notes.limit"),
                List.of("acme-notes"),
                List.of("acme_notes_create")));

        assertThat(registry.manifestVersion()).isNotEqualTo(before);
        assertThat(lastEvent.get()).isNotNull();
        assertThat(lastEvent.get().manifestVersion()).isEqualTo(registry.manifestVersion());

        String afterAdd = registry.manifestVersion();
        registry.removePlugin("acme-notes");
        assertThat(registry.manifestVersion()).isNotEqualTo(afterAdd);
    }

    @Test
    void removingAPluginThatWasNeverActiveIsANoOp() {
        FeatureRegistry registry = registry(new AtomicReference<>());
        String before = registry.manifestVersion();

        registry.removePlugin("no-such-plugin");

        assertThat(registry.manifestVersion()).isEqualTo(before);
    }

    @Test
    void aPermissionCollisionWithAnotherActivePluginFailsOnlyTheSecondActivation() {
        FeatureRegistry registry = registry(new AtomicReference<>());
        registry.addPlugin(descriptor("acme-a", List.of("shared:action"), List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> registry.addPlugin(
                        descriptor("acme-b", List.of("shared:action"), List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared:action");

        // acme-a's own registration must be untouched by acme-b's failed one.
        assertThatThrownBy(() -> registry.addPlugin(
                        descriptor("acme-c", List.of("shared:action"), List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPluginIdCollidingWithAnInstalledModuleIsRejected() {
        FeatureRegistry registry = new FeatureRegistry(
                new InstalledFeatures(List.of(FeatureDescriptor.builder()
                        .id("queues")
                        .title("Queues")
                        .kind(FeatureDescriptor.Kind.FEATURE)
                        .build())),
                new MockEnvironment(),
                event -> {});

        assertThatThrownBy(() -> registry.addPlugin(descriptor("queues", List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queues");
    }
}
