package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A user's snapshot is dropped by an announced change, even one that lands while it is being loaded. */
class AccessLoaderTest {

    final AppUserRepository users = mock(AppUserRepository.class);
    final GrantLoader grants = mock(GrantLoader.class);
    final TeamMemberRepository members = mock(TeamMemberRepository.class);
    final AccessLoader loader = new AccessLoader(users, grants, members, mock(RolePermissionRepository.class));

    final UUID userId = UUID.randomUUID();
    final Grant read = new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.CLUSTER_READ));
    final AtomicReference<Set<Grant>> stored = new AtomicReference<>(Set.of());

    AccessLoaderTest() {
        AppUserEntity user = AppUserEntity.local("u", null, "{noop}x");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(members.findHeldBy(userId)).thenReturn(List.of());
    }

    @Test
    void aSnapshotIsKeptUntilItIsInvalidated() {
        when(grants.loadFor(userId)).thenAnswer(i -> stored.get());
        assertThat(loader.of(userId).grants()).isEmpty();

        stored.set(Set.of(read));
        assertThat(loader.of(userId).grants()).isEmpty();

        loader.invalidate(userId);
        assertThat(loader.of(userId).grants()).containsExactly(read);
    }

    @Test
    void aSnapshotLoadedWhileAChangeWasAnnouncedIsNotKept() {
        when(grants.loadFor(userId)).thenAnswer(i -> {
            Set<Grant> old = stored.get();
            loader.invalidate(null);
            stored.set(Set.of(read));
            return old;
        });

        assertThat(loader.of(userId).grants()).as("answered from what it read").isEmpty();

        when(grants.loadFor(userId)).thenAnswer(i -> stored.get());
        assertThat(loader.of(userId).grants()).as("the next call reads again").containsExactly(read);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.atLeastOnce()).findById(any());
    }

    @Test
    void anotherUsersChangeAlsoKeepsAnInFlightLoadOutOfTheCache() {
        when(grants.loadFor(userId)).thenAnswer(i -> {
            Set<Grant> old = stored.get();
            loader.invalidate(UUID.randomUUID());
            stored.set(Set.of(read));
            return old;
        });
        loader.of(userId);

        when(grants.loadFor(userId)).thenAnswer(i -> stored.get());
        assertThat(loader.of(userId).grants()).containsExactly(read);
    }
}
