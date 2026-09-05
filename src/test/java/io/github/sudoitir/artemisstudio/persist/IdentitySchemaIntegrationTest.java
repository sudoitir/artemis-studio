package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code 014-identity.sql} applies cleanly on top of {@code 003-identity.sql}
 * / {@code 002-estate.sql} (Liquibase runs this for every test in the suite;
 * this test asserts what it produced), the three built-in roles are seeded
 * with the expected permissions, and the new entities round-trip
 * (identity-and-sessions spec, authorization spec, api-tokens spec).
 */
class IdentitySchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    AppUserRepository users;

    @Autowired
    ApiTokenRepository tokens;

    @Autowired
    EnvironmentRepository environments;

    @Test
    void builtinRolesAreSeeded() {
        List<RoleEntity> all = roles.findAllByOrderByName();
        assertThat(all).extracting(RoleEntity::getName).contains("ADMIN", "OPERATOR", "VIEWER");
        assertThat(all).filteredOn(RoleEntity::isBuiltin).hasSize(3);
    }

    @Test
    void adminHoldsTheFullWildcard() {
        RoleEntity admin = roles.findByName("ADMIN").orElseThrow();
        assertThat(rolePermissions.findByIdRoleId(admin.getId()))
                .extracting(RolePermissionEntity::getAction)
                .containsExactly("*");
    }

    @Test
    void viewerHoldsOnlyReadPermissions() {
        RoleEntity viewer = roles.findByName("VIEWER").orElseThrow();
        List<String> actions = rolePermissions.findByIdRoleId(viewer.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .toList();
        assertThat(actions).isNotEmpty().allMatch(a -> a.endsWith(":read"));
    }

    @Test
    void anOidcUserHasNoPasswordHash() {
        AppUserEntity user = users.save(AppUserEntity.oidc("oidc-" + UUID.randomUUID(), null, "issuer-1", "sub-1"));
        AppUserEntity reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNull();
        assertThat(reloaded.getAuthSource()).isEqualTo("OIDC");
        users.delete(reloaded);
    }

    @Test
    void anApiTokenRoundTrips() {
        AppUserEntity owner = users.save(AppUserEntity.local("token-owner-" + UUID.randomUUID(), null, "{noop}x"));
        ApiTokenEntity token =
                tokens.save(new ApiTokenEntity(owner.getId(), "ci", "as_abcdefghijk", new byte[32], null));
        assertThat(tokens.findByPrefix("as_abcdefghijk")).isPresent();
        assertThat(token.isActive(Instant.now())).isTrue();

        tokens.delete(token);
        users.delete(owner);
    }

    @Test
    void anEnvironmentRoundTrips() {
        EnvironmentEntity env = environments.save(new EnvironmentEntity("env-" + UUID.randomUUID(), "#336699", 5));
        assertThat(environments.findById(env.getId())).isPresent();
        environments.delete(env);
    }
}
