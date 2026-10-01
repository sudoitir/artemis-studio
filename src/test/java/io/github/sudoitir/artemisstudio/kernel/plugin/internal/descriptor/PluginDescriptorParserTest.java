package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What {@code plugin.json} may say about a license (ADR-0153). */
class PluginDescriptorParserTest {

    private final PluginDescriptorParser parser = new PluginDescriptorParser();
    private final JsonMapper json = JsonMapper.builder().build();

    private byte[] descriptor(String key, Object value) {
        Map<String, Object> descriptor = PluginJarBuilder.defaultDescriptor("acme-notes");
        if (key != null) {
            descriptor.put(key, value);
        }
        return json.writeValueAsBytes(descriptor);
    }

    @Test
    void aPluginThatDoesNotSayItNeedsALicenseDoesNot() throws Exception {
        assertThat(parser.parse(descriptor(null, null)).isRequiresLicense()).isFalse();
    }

    @Test
    void aPluginThatSaysItNeedsALicenseDoes() throws Exception {
        assertThat(parser.parse(descriptor("requiresLicense", true)).isRequiresLicense())
                .isTrue();
        assertThat(parser.parse(descriptor("requiresLicense", false)).isRequiresLicense())
                .isFalse();
    }

    @Test
    void aRequirementThatIsNotABooleanIsRefused() {
        assertThatThrownBy(() -> parser.parse(descriptor("requiresLicense", "yes")))
                .isInstanceOf(PluginDescriptorException.class);
        assertThatThrownBy(() -> parser.parse(descriptor("requiresLicense", Map.of("until", "2030"))))
                .isInstanceOf(PluginDescriptorException.class);
    }
}
