package io.github.sudoitir.artemisstudio.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.Security;
import io.github.sudoitir.artemisstudio.persist.OidcRoleMappingEntity;
import io.github.sudoitir.artemisstudio.persist.OidcRoleMappingRepository;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ScopeIds;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The claim -> role reconciliation logic in isolation (task 9.7) — mocked
 * repositories, since {@link ClaimRoleMapper} has no schema-shaped behavior of
 * its own to verify against a real Postgres.
 */
@ExtendWith(MockitoExtension.class)
class ClaimRoleMapperTest {

    @Mock
    OidcRoleMappingRepository mappings;

    @Mock
    RoleRepository roles;

    @Mock
    UserRoleRepository userRoles;

    ClaimRoleMapper mapper;

    UUID userId;
    UUID operatorRoleId;
    UUID viewerRoleId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        operatorRoleId = UUID.randomUUID();
        viewerRoleId = UUID.randomUUID();
    }

    private ClaimRoleMapper mapperWithDefaultRole(String defaultRole) {
        ArtemisStudioProperties properties = new ArtemisStudioProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new Security(Duration.ofHours(8), "groups", defaultRole));
        return new ClaimRoleMapper(mappings, roles, userRoles, properties);
    }

    @Test
    void mapsAMatchingClaimValueToItsConfiguredGrant() {
        when(mappings.findByClaim("groups"))
                .thenReturn(
                        List.of(new OidcRoleMappingEntity("groups", "eng", operatorRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());

        ClaimRoleMapper.Outcome outcome = mapperWithDefaultRole(null).apply(userId, Map.of("groups", List.of("eng")));

        assertThat(outcome).isEqualTo(ClaimRoleMapper.Outcome.MAPPED);
        verify(userRoles).save(argThatMatches(operatorRoleId, "GLOBAL", ScopeIds.GLOBAL));
    }

    @Test
    void unionsGrantsAcrossMultipleMatchingClaimValues() {
        when(mappings.findByClaim("groups"))
                .thenReturn(List.of(
                        new OidcRoleMappingEntity("groups", "eng", operatorRoleId, "GLOBAL", ScopeIds.GLOBAL),
                        new OidcRoleMappingEntity("groups", "support", viewerRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());

        ClaimRoleMapper.Outcome outcome =
                mapperWithDefaultRole(null).apply(userId, Map.of("groups", List.of("eng", "support")));

        assertThat(outcome).isEqualTo(ClaimRoleMapper.Outcome.MAPPED);
        verify(userRoles, times(2)).save(any());
    }

    @Test
    void removesAPreviouslyMappedGrantWhenTheClaimNoLongerMatchesIt() {
        when(mappings.findByClaim("groups"))
                .thenReturn(
                        List.of(new OidcRoleMappingEntity("groups", "eng", operatorRoleId, "GLOBAL", ScopeIds.GLOBAL)));
        UserRoleEntity existing = new UserRoleEntity(userId, operatorRoleId, "GLOBAL", ScopeIds.GLOBAL);
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of(existing));

        // The user's claim value changed and no longer matches "eng".
        ClaimRoleMapper.Outcome outcome =
                mapperWithDefaultRole("VIEWER").apply(userId, Map.of("groups", List.of("other")));

        assertThat(outcome).isEqualTo(ClaimRoleMapper.Outcome.DEFAULTED);
        verify(userRoles).deleteById(existing.getId());
    }

    @Test
    void leavesAnUnrelatedHandGrantedRoleAlone() {
        UUID handGrantedRoleId = UUID.randomUUID();
        when(mappings.findByClaim("groups")).thenReturn(List.of());
        UserRoleEntity handGranted = new UserRoleEntity(userId, handGrantedRoleId, "GLOBAL", ScopeIds.GLOBAL);
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of(handGranted));
        when(roles.findByName("VIEWER")).thenReturn(Optional.of(roleNamed("VIEWER", viewerRoleId)));
        when(userRoles.findById(any())).thenReturn(Optional.empty());

        mapperWithDefaultRole("VIEWER").apply(userId, Map.of("groups", List.of()));

        verify(userRoles, never()).deleteById(handGranted.getId());
    }

    @Test
    void fallsBackToTheDefaultRoleWhenNoMappingMatches() {
        when(mappings.findByClaim("groups")).thenReturn(List.of());
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());
        when(roles.findByName("VIEWER")).thenReturn(Optional.of(roleNamed("VIEWER", viewerRoleId)));
        when(userRoles.findById(any())).thenReturn(Optional.empty());

        ClaimRoleMapper.Outcome outcome = mapperWithDefaultRole("VIEWER").apply(userId, Map.of());

        assertThat(outcome).isEqualTo(ClaimRoleMapper.Outcome.DEFAULTED);
        verify(userRoles).save(argThatMatches(viewerRoleId, "GLOBAL", ScopeIds.GLOBAL));
    }

    @Test
    void refusesWhenNoMappingMatchesAndNoDefaultRoleIsConfigured() {
        when(mappings.findByClaim("groups")).thenReturn(List.of());

        ClaimRoleMapper.Outcome outcome = mapperWithDefaultRole(null).apply(userId, Map.of());

        assertThat(outcome).isEqualTo(ClaimRoleMapper.Outcome.REFUSED);
        verify(userRoles, never()).save(any());
    }

    private static RoleEntity roleNamed(String name, UUID id) {
        RoleEntity role = new RoleEntity(name, true);
        try {
            var field = RoleEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return role;
    }

    private static UserRoleEntity argThatMatches(UUID roleId, String scopeType, UUID scopeId) {
        return org.mockito.ArgumentMatchers.argThat(saved -> saved.getRoleId().equals(roleId)
                && saved.getScopeType().equals(scopeType)
                && saved.getScopeId().equals(scopeId));
    }
}
