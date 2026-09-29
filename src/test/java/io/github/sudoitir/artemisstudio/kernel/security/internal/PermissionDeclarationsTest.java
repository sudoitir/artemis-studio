package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;

/** The permission declaration check (operational-health spec): each mismatch kind, and healthy. */
class PermissionDeclarationsTest {

    static class Guarded {
        @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
        public void constant() {}

        @PreAuthorize("@perm.can(#clusterId, 'queue:read') and @perm.can(#clusterId, #permission)")
        public void literalWithCluster(String clusterId, String permission) {}

        @PreAuthorize("@perm.can('*')")
        public void wildcard() {}
    }

    private final FeatureRegistry registry = mock(FeatureRegistry.class);
    private final PermissionDeclarations declarations = new PermissionDeclarations(registry);

    private void catalogue(CatalogueEntry... entries) {
        when(registry.catalogue()).thenReturn(List.of(entries));
    }

    private static CatalogueEntry entry(String action, boolean globalOnly) {
        return new CatalogueEntry(action, "Does " + action, "security", "Security", globalOnly);
    }

    private void studioBeans() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(Guarded.class);
        context.refresh();
        ApplicationReadyEvent ready = mock(ApplicationReadyEvent.class);
        when(ready.getApplicationContext()).thenReturn(context);
        declarations.onReady(ready);
    }

    @Test
    void guardsReadConstantsAndLiteralsAndSkipVariablesAndWildcards() {
        assertThat(GuardPermissions.of(Guarded.class, getClass().getClassLoader()))
                .extracting(GuardPermissions.GuardRef::permission, GuardPermissions.GuardRef::withCluster)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Permissions.USER_ADMIN, false),
                        org.assertj.core.groups.Tuple.tuple("queue:read", true));
    }

    @Test
    void healthyWhenEveryGuardedPermissionIsRegisteredAndDescribed() {
        catalogue(entry(Permissions.USER_ADMIN, true), entry("queue:read", false));
        studioBeans();

        assertThat(declarations.mismatches()).isEmpty();
    }

    @Test
    void reportsAGuardNamingAnUnregisteredPermission() {
        catalogue(entry(Permissions.USER_ADMIN, true));
        studioBeans();

        assertThat(declarations.mismatches())
                .singleElement()
                .asString()
                .contains("'queue:read'", "Guarded#literalWithCluster", "not in the permission catalogue");
    }

    @Test
    void reportsAGlobalOnlyPermissionCheckedAgainstACluster() {
        catalogue(entry(Permissions.USER_ADMIN, true), entry("queue:read", true));
        studioBeans();

        assertThat(declarations.mismatches()).singleElement().asString().contains("global-only");
    }

    @Test
    void reportsAnUndescribedEntry() {
        catalogue(new CatalogueEntry("queue:read", " ", "queues", "Queues", false));

        assertThat(declarations.mismatches()).singleElement().asString().contains("no description");
    }

    @Test
    void reportsAPluginManifestReferenceUntilThePluginDeactivates() {
        catalogue(entry("acme:read", false));
        PluginDescriptor descriptor = mock(PluginDescriptor.class);
        when(descriptor.id()).thenReturn("acme");
        when(descriptor.basePackage()).thenReturn("com.acme");
        when(descriptor.mcpTools()).thenReturn(List.of());
        when(descriptor.metrics())
                .thenReturn(List.of(new PluginDescriptor.Metric("acme:count", "Count", "count", "queue", "acme:gone")));
        PluginHandle handle = mock(PluginHandle.class);
        when(handle.id()).thenReturn("acme");
        when(handle.descriptor()).thenReturn(descriptor);
        when(handle.applicationContext()).thenReturn(new GenericApplicationContext());
        when(handle.classLoader()).thenReturn(getClass().getClassLoader());

        declarations.attach(handle);
        assertThat(declarations.mismatches()).singleElement().asString().contains("'acme:gone'", "plugin acme");

        declarations.detach(handle);
        assertThat(declarations.mismatches()).isEmpty();
    }
}
