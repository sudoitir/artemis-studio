package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The published {@code plugin.schema.json} is documentation for plugin authors; this test is the
 * drift guard that keeps it honest against the record {@link PluginDescriptorParser} actually
 * enforces (task 5.3).
 */
class PluginDescriptorSchemaDriftTest {

    @Test
    void schemaPropertiesMatchTheRecordComponents() throws Exception {
        var mapper = JsonMapper.builder().build();
        ObjectNode schema = (ObjectNode)
                mapper.readTree(new ClassPathResource("META-INF/artemis-studio/plugin.schema.json").getInputStream());
        Set<String> schemaProperties =
                new LinkedHashSet<>(schema.get("properties").propertyNames());

        Set<String> recordComponents = Stream.of(PluginDescriptor.class.getRecordComponents())
                .map(c -> c.getName())
                .collect(Collectors.toSet());

        assertThat(schemaProperties).containsExactlyInAnyOrderElementsOf(recordComponents);
    }
}
