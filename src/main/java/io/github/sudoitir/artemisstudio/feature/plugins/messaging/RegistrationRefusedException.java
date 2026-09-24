package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * A registration or send Studio will not accept; the message says why in words an operator can act
 * on — a missing permission, an unknown cluster, a reserved queue.
 */
@PluginApi
public class RegistrationRefusedException extends RuntimeException {
    public RegistrationRefusedException(String message) {
        super(message);
    }
}
