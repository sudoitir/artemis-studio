package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * One permission string a module checks (ADR-0038), with its role-editor label.
 *
 * @param globalOnly the permission's guards check it without a cluster, so only a global grant
 *     makes it take effect. Descriptive: it never changes how a grant resolves.
 */
public record PermissionDef(String action, String label, boolean globalOnly) {

    public PermissionDef(String action, String label) {
        this(action, label, false);
    }
}
