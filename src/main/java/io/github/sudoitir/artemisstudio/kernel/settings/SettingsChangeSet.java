package io.github.sudoitir.artemisstudio.kernel.settings;

import java.util.List;

/**
 * The parameters of the gated {@code settings.apply} operation: the changes of one change set, sorted by key.
 *
 * @param changes the changes, applied together or not at all
 */
public record SettingsChangeSet(List<SettingChange> changes) {

    public SettingsChangeSet {
        changes = List.copyOf(changes);
    }
}
