package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code META-INF/artemis-studio/plugin.json} (task 5.3). Unknown fields fail the parse —
 * they are the most common author typo, and silently dropping one would install a plugin whose
 * author thought a field took effect.
 */
@Component
public class PluginDescriptorParser {

    /** design.md §3: {@code plugin.json} is limited to 256 KB. */
    public static final int MAX_BYTES = 256 * 1024;

    private final JsonMapper mapper = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    public PluginDescriptor parse(byte[] json) throws PluginDescriptorException {
        if (json.length > MAX_BYTES) {
            throw new PluginDescriptorException(
                    "plugin.json is %d bytes, over the %d byte limit".formatted(json.length, MAX_BYTES));
        }
        try {
            return mapper.readValue(json, PluginDescriptor.class);
        } catch (JacksonException e) {
            throw new PluginDescriptorException("plugin.json failed to parse: " + e.getMessage(), e);
        }
    }
}
