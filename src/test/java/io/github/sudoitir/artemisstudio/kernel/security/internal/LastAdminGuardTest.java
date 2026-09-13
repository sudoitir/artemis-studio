package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The safety net for the fully-dynamic permission model (authorization spec,
 * design.md decision 4): the last enabled global administrator cannot be
 * disabled or stripped of that grant, and nobody can revoke their own.
 */
@ExtendWith(MockitoExtension.class)
class LastAdminGuardTest {

    @Mock
    AppUserRepository users;

    @Mock
    RoleRepository roles;

    @Mock
    UserRoleRepository userRoles;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AdministrationAudit audit;

    UserService service;

    UUID adminRoleId = UUID.randomUUID();
    RoleEntity adminRole;

    @BeforeEach
    void setUp() {
        service = new UserService(users, roles, userRoles, passwordEncoder, audit);
        adminRole = role(adminRoleId, "ADMIN");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cannotDisableTheOnlyEnabledAdmin() {
        UUID adminUserId = UUID.randomUUID();
        AppUserEntity admin = user(adminUserId, "admin", false);
        when(users.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRoles.findByIdRoleId(adminRoleId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(adminUserId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));

        assertThatThrownBy(() -> service.setDisabled(adminUserId, true))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("last-admin");
    }

    @Test
    void canDisableAnAdminWhenAnotherEnabledAdminRemains() {
        UUID adminUserId = UUID.randomUUID();
        UUID otherAdminId = UUID.randomUUID();
        AppUserEntity admin = user(adminUserId, "admin", false);
        when(users.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(users.findById(otherAdminId)).thenReturn(Optional.of(user(otherAdminId, "admin2", false)));
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRoles.findByIdRoleId(adminRoleId))
                .thenReturn(List.of(
                        new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL),
                        new UserRoleEntity(otherAdminId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(adminUserId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));

        service.setDisabled(adminUserId, true);
        assertThat(admin.isDisabled()).isTrue();
    }

    @Test
    void cannotStripTheLastAdminsGrant() {
        UUID adminUserId = UUID.randomUUID();
        AppUserEntity admin = user(adminUserId, "admin", false);
        when(users.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(roles.findById(adminRoleId)).thenReturn(Optional.of(adminRole));
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRoles.findByIdRoleId(adminRoleId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(adminUserId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        authenticateAs(UUID.randomUUID()); // a *different* admin performing the removal

        assertThatThrownBy(() -> service.removeGrant(adminUserId, adminRoleId, "GLOBAL", null))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("last-admin");
    }

    @Test
    void cannotRevokeOwnAdminGrantEvenIfNotTheLastAdmin() {
        UUID adminUserId = UUID.randomUUID();
        UUID otherAdminId = UUID.randomUUID();
        AppUserEntity admin = user(adminUserId, "admin", false);
        when(users.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(users.findById(otherAdminId)).thenReturn(Optional.of(user(otherAdminId, "admin2", false)));
        when(roles.findById(adminRoleId)).thenReturn(Optional.of(adminRole));
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRoles.findByIdRoleId(adminRoleId))
                .thenReturn(List.of(
                        new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL),
                        new UserRoleEntity(otherAdminId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(adminUserId))
                .thenReturn(List.of(new UserRoleEntity(adminUserId, adminRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        authenticateAs(adminUserId); // the admin trying to self-revoke

        assertThatThrownBy(() -> service.removeGrant(adminUserId, adminRoleId, "GLOBAL", null))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("self-revoke-admin");
    }

    private void authenticateAs(UUID userId) {
        StudioPrincipal principal = new StudioPrincipal(userId, "someone", Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static AppUserEntity user(UUID id, String username, boolean disabled) {
        AppUserEntity u = AppUserEntity.local(username, null, "hash");
        setId(u, id);
        if (disabled) {
            u.setDisabled(true);
        }
        return u;
    }

    private static RoleEntity role(UUID id, String name) {
        RoleEntity r = new RoleEntity(name, true);
        setId(r, id);
        return r;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
