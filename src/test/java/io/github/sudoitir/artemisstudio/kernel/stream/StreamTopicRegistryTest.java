package io.github.sudoitir.artemisstudio.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@link StreamTopicRegistry#addPlugin} / {@link StreamTopicRegistry#removePlugin} (design.md, task 6.4). */
class StreamTopicRegistryTest {

    private static final io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry BUILTIN_FEATURES =
            new io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry(
                    new InstalledFeatures(List.of(FeatureDescriptor.builder()
                            .id("queues")
                            .title("Queues")
                            .kind(FeatureDescriptor.Kind.FEATURE)
                            .streamTopic(TopicDef.signal("queues"))
                            .build())),
                    new MockEnvironment(),
                    event -> {});

    private record FixedReplay(String topic) implements EventReplay {
        @Override
        public List<Replayed> since(UUID clusterId, long lastEventId, int cap) {
            return List.of(new Replayed("1", "payload"));
        }
    }

    @Test
    void addPluginAddsItsTopicsAndReplaysAndRemovePluginTakesThemBackOut() {
        StreamTopicRegistry registry = new StreamTopicRegistry(BUILTIN_FEATURES, List.of());
        assertThat(registry.known()).containsExactly("queues");

        registry.addPlugin("acme-notes", List.of("acme-notes"), List.of(new FixedReplay("acme-notes")));

        assertThat(registry.known()).containsExactlyInAnyOrder("queues", "acme-notes");
        assertThat(registry.replays()).containsKey("acme-notes");
        // The built-in default subscription (no ?topics=) never silently picks up a plugin topic.
        assertThat(registry.defaultTopics()).doesNotContain("acme-notes");

        registry.removePlugin("acme-notes");

        assertThat(registry.known()).containsExactly("queues");
        assertThat(registry.replays()).doesNotContainKey("acme-notes");
    }

    @Test
    void removingAPluginThatNeverAttachedIsANoOp() {
        StreamTopicRegistry registry = new StreamTopicRegistry(BUILTIN_FEATURES, List.of());
        registry.removePlugin("never-attached");
        assertThat(registry.known()).containsExactly("queues");
    }
}
