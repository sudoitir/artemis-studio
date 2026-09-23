package io.github.sudoitir.artemisstudio.kernel.plugin.internal.store;

/** A stored artifact could not be found, or a materialized copy did not match its own checksum. */
public class PluginStoreException extends RuntimeException {

    public PluginStoreException(String message) {
        super(message);
    }

    public PluginStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
