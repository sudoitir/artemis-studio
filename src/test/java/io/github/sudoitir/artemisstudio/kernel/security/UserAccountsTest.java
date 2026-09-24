package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The first administrator is created once, on an empty user store, must change its password, and
 * can install plugins (ADR-0103).
 */
@ExtendWith(MockitoExtension.class)
class UserAccountsTest {

    @Mock
    AppUserRepository users;

    @Mock
    RoleRepository roles;

    @Mock
    UserRoleRepository userRoles;

    @Mock
    PluginInstallers installers;

    @Test
    void theFirstAdministratorMustChangeThePasswordAndHoldsTheGlobalAdminRole() {
        when(users.count()).thenReturn(0L);
        UUID adminUserId = UUID.randomUUID();
        when(users.saveAndFlush(any())).thenAnswer(inv -> {
            AppUserEntity saved = inv.getArgument(0);
            setId(saved, AppUserEntity.class, adminUserId);
            return saved;
        });
        UUID adminRoleId = UUID.randomUUID();
        RoleEntity adminRole = new RoleEntity("ADMIN", true);
        setId(adminRole, RoleEntity.class, adminRoleId);
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));

        boolean created = new UserAccounts(users, roles, userRoles, installers)
                .createFirstAdministrator("admin", "{bcrypt}hashed");

        assertThat(created).isTrue();
        verify(users)
                .saveAndFlush(argThat(u -> u.getUsername().equals("admin")
                        && u.isMustChangePassword()
                        && "{bcrypt}hashed".equals(u.getPasswordHash())));
        verify(userRoles)
                .save(argThat(ur -> ur.getUserId().equals(adminUserId)
                        && ur.getRoleId().equals(adminRoleId)
                        && "GLOBAL".equals(ur.getScopeType())));
        verify(installers).grant(adminUserId, "bootstrap");
    }

    @Test
    void noAdministratorIsCreatedOnceAnyAccountExists() {
        when(users.count()).thenReturn(1L);

        boolean created = new UserAccounts(users, roles, userRoles, installers)
                .createFirstAdministrator("admin", "{bcrypt}hashed");

        assertThat(created).isFalse();
        verify(users, never()).saveAndFlush(any());
        verify(userRoles, never()).save(any());
        verify(installers, never()).grant(any(), any());
    }

    private static <T> void setId(T target, Class<T> type, UUID id) {
        try {
            var field = type.getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
