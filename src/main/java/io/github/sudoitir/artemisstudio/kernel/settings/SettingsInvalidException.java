package io.github.sudoitir.artemisstudio.kernel.settings;

import java.util.Map;

/** A change set holds values that are not allowed; nothing was changed. */
public class SettingsInvalidException extends IllegalArgumentException {

    private final transient Map<String, String> fieldErrors;

    /** @param fieldErrors the reason for each invalid setting, by key */
    public SettingsInvalidException(Map<String, String> fieldErrors) {
        super(
                fieldErrors.size() == 1
                        ? fieldErrors.values().iterator().next()
                        : "One or more settings are invalid: " + String.join("; ", fieldErrors.values()));
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
