package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** An approver's decision on a held operation. A rejection carries a reason. */
@PluginApi
public enum Vote {
    APPROVE,
    REJECT
}
