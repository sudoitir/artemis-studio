package io.github.sudoitir.artemisstudio.feature.plugins;

/**
 * A plugin action the caller may not take, with the reason as a stable slug (ADR-0103):
 * {@code plugin-install-interactive-only}, {@code plugin-installer-required},
 * {@code plugin-upload-disabled} or {@code plugin-upload-rate-limited}.
 */
public class PluginAccessDeniedException extends RuntimeException {

    private final String slug;

    public PluginAccessDeniedException(String slug, String message) {
        super(message);
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
