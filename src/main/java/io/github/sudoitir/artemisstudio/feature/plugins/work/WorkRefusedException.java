package io.github.sudoitir.artemisstudio.feature.plugins.work;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Work Studio will not accept or turn on: its owner lacks a need, and the message names each one with
 * its resource, or the work is unknown.
 */
@PluginApi
public class WorkRefusedException extends RuntimeException {
    public WorkRefusedException(String message) {
        super(message);
    }
}
