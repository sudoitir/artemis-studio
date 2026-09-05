package io.github.sudoitir.artemisstudio.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Task 3.13: bootstraps exactly once, on an empty {@code app_user}, never again on a populated one. */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    AppUserRepository users;

    @Mock
    RoleRepository roles;

    @Mock
    UserRoleRepository userRoles;

    @Mock
    PasswordEncoder passwordEncoder;

    @Test
    void createsAnAdminWithAForcedPasswordChangeWhenNoUsersExist() {
        when(users.count()).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hashed");
        UUID adminUserId = UUID.randomUUID();
        when(users.save(any())).thenAnswer(inv -> {
            AppUserEntity saved = inv.getArgument(0);
            setId(saved, adminUserId);
            return saved;
        });
        UUID adminRoleId = UUID.randomUUID();
        RoleEntity adminRole = new RoleEntity("ADMIN", true);
        setRoleId(adminRole, adminRoleId);
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));

        new AdminBootstrap(users, roles, userRoles, passwordEncoder).bootstrapIfEmpty();

        verify(users)
                .save(org.mockito.ArgumentMatchers.argThat(u -> u.getUsername().equals("admin")
                        && u.isMustChangePassword()
                        && "{bcrypt}hashed".equals(u.getPasswordHash())));
        verify(userRoles)
                .save(org.mockito.ArgumentMatchers.argThat(ur ->
                        ur.getUserId().equals(adminUserId) && ur.getRoleId().equals(adminRoleId)));
    }

    @Test
    void doesNothingWhenAnAccountAlreadyExists() {
        when(users.count()).thenReturn(1L);

        new AdminBootstrap(users, roles, userRoles, passwordEncoder).bootstrapIfEmpty();

        verify(users, never()).save(any());
        verify(userRoles, never()).save(any());
    }

    private static void setId(AppUserEntity user, UUID id) {
        try {
            var field = AppUserEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setRoleId(RoleEntity role, UUID id) {
        try {
            var field = RoleEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
