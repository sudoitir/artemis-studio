package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

/** {@code plugin.json} could not be read: too large, malformed, an unknown field, or a missing one. */
public class PluginDescriptorException extends Exception {

    public PluginDescriptorException(String message) {
        super(message);
    }

    public PluginDescriptorException(String message, Throwable cause) {
        super(message, cause);
    }
}
