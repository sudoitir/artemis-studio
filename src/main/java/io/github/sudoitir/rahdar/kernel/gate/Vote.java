package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** An approver's decision on a held operation. A rejection carries a reason. */
@PluginApi
public enum Vote {
    APPROVE,
    REJECT
}
