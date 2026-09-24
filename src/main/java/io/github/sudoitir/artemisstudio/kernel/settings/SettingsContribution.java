package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * The runtime settings one module owns (ADR-0047). Every key must also be declared
 * in that module's descriptor, so a disabled module's settings can be recognised —
 * and refused — without its beans.
 */
@PluginApi
public interface SettingsContribution {

    /** The id of the module whose descriptor declares these keys. */
    String featureId();

    /** In the order the settings screen renders them. */
    List<SettingDef> settings();
}
