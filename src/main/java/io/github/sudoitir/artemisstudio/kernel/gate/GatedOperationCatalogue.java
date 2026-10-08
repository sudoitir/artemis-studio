package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * Every operation type the gate can ask about: Studio's own and those of the plugins attached on this replica, so
 * an approval provider can build policy editors and a console can show what approvals cover. A plugin that is
 * detached drops out of the list.
 */
@PluginApi
public interface GatedOperationCatalogue {

    /** The operation types now known, ordered by type. */
    List<GatedOperationInfo> list();
}
