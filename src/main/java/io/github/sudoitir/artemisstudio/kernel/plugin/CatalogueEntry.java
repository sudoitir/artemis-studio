package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * One permission in the installation's catalogue ({@link FeatureRegistry#catalogue()}), attributed
 * to the enabled module or active plugin that declares it.
 */
public record CatalogueEntry(
        String action, String description, String featureId, String featureTitle, boolean globalOnly) {

    static CatalogueEntry of(FeatureDescriptor owner, PermissionDef permission) {
        return new CatalogueEntry(
                permission.action(),
                permission.label(),
                owner.id(),
                owner.title() == null ? owner.id() : owner.title(),
                permission.globalOnly());
    }
}
