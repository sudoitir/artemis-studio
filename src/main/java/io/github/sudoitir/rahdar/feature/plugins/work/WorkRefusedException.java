package io.github.sudoitir.rahdar.feature.plugins.work;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * Work Rahdar will not accept or turn on: its owner lacks a need, and the message names each one with
 * its resource, or the work is unknown.
 */
@PluginApi
public class WorkRefusedException extends RuntimeException {
    public WorkRefusedException(String message) {
        super(message);
    }
}
