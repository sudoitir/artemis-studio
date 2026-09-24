package com.acme.notes;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A setting an administrator changes on the Settings page, under Plugins, while Studio runs.
 * Its key starts with the plugin's id and is declared in plugin.json.
 */
@Component
public class NotesSettings implements SettingsContribution {

    @Override
    public String featureId() {
        return "acme-notes";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(new SettingDef(
                "acme-notes.max-length",
                "plugins",
                "Longest note",
                "The most characters a note may have.",
                SettingDef.Kind.INT,
                () -> "2000",
                null));
    }
}
