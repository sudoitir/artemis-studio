package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A permission's scope and the kinds it acts on cannot disagree (authorization spec). */
class PermissionDefTest {

    @Test
    void aResourcePermissionNeedsAKind() {
        assertThatThrownBy(() -> new PermissionDef("queue:read", "Read", PermissionScope.RESOURCE, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("queue:read", "kind");
    }

    @Test
    void onlyAResourcePermissionNamesKinds() {
        Set<ResourceKind> kinds = Set.of(ResourceKind.QUEUE);
        Set<String> noRequirements = Set.of();
        assertThatThrownBy(
                        () -> new PermissionDef("cluster:read", "Read", PermissionScope.CLUSTER, kinds, noRequirements))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cluster:read");
    }

    @Test
    void theFactoriesSetScopeKindsAndRequirements() {
        PermissionDef def = PermissionDef.resource("queue:purge", "Purge", ResourceKind.QUEUE, "queue:read");

        assertThat(def.scope()).isEqualTo(PermissionScope.RESOURCE);
        assertThat(def.resourceKinds()).containsExactly(ResourceKind.QUEUE);
        assertThat(def.requires()).containsExactly("queue:read");
        assertThat(PermissionDef.global("user:admin", "Admin").requires()).isEmpty();
        assertThat(PermissionDef.cluster("cluster:write", "Write", "cluster:read")
                        .requires())
                .containsExactly("cluster:read");
    }
}
