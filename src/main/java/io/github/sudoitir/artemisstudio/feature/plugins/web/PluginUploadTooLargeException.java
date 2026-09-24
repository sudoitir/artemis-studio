package io.github.sudoitir.artemisstudio.feature.plugins.web;

/** An upload past the 50 MB a plugin jar may be (design.md §7). */
class PluginUploadTooLargeException extends RuntimeException {

    PluginUploadTooLargeException() {
        super("A plugin jar may be at most 50 MB.");
    }
}
