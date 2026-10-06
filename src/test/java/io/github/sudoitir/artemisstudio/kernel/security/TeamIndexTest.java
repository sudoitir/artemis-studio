package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The index is rebuilt after a change, and never keeps what was read from before one (design D12). */
class TeamIndexTest {

    final TeamPatternRepository patterns = mock(TeamPatternRepository.class);
    final TeamShareRepository shares = mock(TeamShareRepository.class);
    final RolePermissionRepository rolePermissions = mock(RolePermissionRepository.class);
    final TeamIndex index = new TeamIndex(patterns, shares, rolePermissions);

    final UUID cluster = UUID.randomUUID();
    final UUID orders = UUID.randomUUID();
    final List<TeamPatternEntity> rows = new ArrayList<>();

    @Test
    void anIndexReadFromDataThatChangedWhileItWasBeingReadIsNotKept() {
        List<TeamPatternEntity> before = List.of();
        when(shares.findAll()).thenReturn(List.of());
        when(patterns.findAll()).thenAnswer(read -> {
            // A change commits and is announced while this read is in flight, so what it returns is old.
            index.invalidate();
            rows.add(new TeamPatternEntity(orders, cluster, "BOTH", "orders.#"));
            return before;
        });

        assertThat(index.ownerOf(cluster, ResourceRef.queue("orders.in")))
                .as("this call may still be answered from what it read")
                .isEmpty();

        when(patterns.findAll()).thenAnswer(read -> List.copyOf(rows));
        assertThat(index.ownerOf(cluster, ResourceRef.queue("orders.in")))
                .as("but the next call reads again and sees the change")
                .contains(orders);
    }

    @Test
    void aBuiltIndexIsKeptUntilItIsInvalidated() {
        rows.add(new TeamPatternEntity(orders, cluster, "BOTH", "orders.#"));
        when(patterns.findAll()).thenAnswer(read -> List.copyOf(rows));
        when(shares.findAll()).thenReturn(List.of());
        assertThat(index.ownerOf(cluster, ResourceRef.queue("orders.in"))).contains(orders);

        rows.clear();
        assertThat(index.ownerOf(cluster, ResourceRef.queue("orders.in"))).contains(orders);

        index.invalidate();
        assertThat(index.ownerOf(cluster, ResourceRef.queue("orders.in"))).isEmpty();
        org.mockito.Mockito.verify(rolePermissions, org.mockito.Mockito.never()).findByIdRoleId(any());
    }
}
