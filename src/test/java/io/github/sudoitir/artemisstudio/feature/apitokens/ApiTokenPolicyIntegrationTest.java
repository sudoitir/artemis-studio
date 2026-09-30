package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Rotation, the live lifetime cap, and the administrators' inventory (api-tokens spec, ADR-0136),
 * through the real token path.
 */
class ApiTokenPolicyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ApiTokenService tokens;

    @Autowired
    SettingsService settings;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        asAdmin(() -> settings.reset(ApiTokensSettings.MAX_LIFETIME));
        auditEvents.deleteAll();
    }

    @Test
    void mintingRefusesNoExpiryAndAnExpiryBeyondTheCap() {
        McpFixture.Key owner = key(Set.of(Permissions.CLUSTER_READ));
        List<Grant> grants = List.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.CLUSTER_READ)));

        UUID ownerId = owner.userId();
        List<String> noTools = List.of();
        Instant beyondCap = Instant.now().plus(Duration.ofDays(91));

        assertThatThrownBy(() -> tokens.mint(ownerId, "none", null, grants, noTools))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.mint(ownerId, "long", beyondCap, grants, noTools))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latest allowed");
    }

    @Test
    void loweringTheCapShortensAnExistingToken() {
        McpFixture.Key key = key(Set.of(Permissions.CLUSTER_READ));
        assertThat(tokens.authenticate(key.plaintext())).isNotNull();

        asAdmin(() -> settings.put(ApiTokensSettings.MAX_LIFETIME, "1ms"));

        assertThat(tokens.authenticate(key.plaintext())).isNull();
    }

    @Test
    void bothSecretsWorkDuringTheOverlapAndTheOldOneIsRejectedAndAuditedAfter() {
        McpFixture.Key key = key(Set.of(Permissions.CLUSTER_READ));
        var before = tokens.listFor(key.userId()).getFirst();

        var rotated = tokens.rotate(key.userId(), before.getId());

        assertThat(tokens.authenticate(key.plaintext())).isNotNull();
        assertThat(tokens.authenticate(rotated.plaintext())).isNotNull();
        assertThat(rotated.entity().getExpiresAt()).isEqualTo(before.getExpiresAt());
        assertThat(tokens.grantsOf(before.getId())).isNotEmpty();

        jdbc.sql("UPDATE api_token SET previous_valid_until = now() - interval '1 second' WHERE id = ?")
                .param(before.getId())
                .update();

        assertThat(tokens.authenticate(key.plaintext())).isNull();
        assertThat(tokens.authenticate(rotated.plaintext())).isNotNull();
        AuditEventEntity rejected = auditEvents.findAll().stream()
                .filter(e -> "TOKEN_REJECTED".equals(e.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(rejected.getUsername()).contains(key.username()).contains("[token: mcp-test-key]");
    }

    @Test
    void aRevokedTokenCannotBeRotated() {
        McpFixture.Key key = key(Set.of(Permissions.CLUSTER_READ));
        var token = tokens.listFor(key.userId()).getFirst();
        UUID userId = key.userId();
        UUID tokenId = token.getId();
        tokens.revoke(userId, tokenId);

        assertThatThrownBy(() -> tokens.rotate(userId, tokenId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void anAdministratorSeesEveryTokenFlagsStaleOnesAndRevokesAnother() throws Exception {
        McpFixture.Key admin = key(Set.of(TokenPermissions.TOKEN_ADMIN));
        McpFixture.Key victim = key(Set.of(Permissions.CLUSTER_READ));
        var leaked = tokens.listFor(victim.userId()).getFirst();
        jdbc.sql("UPDATE api_token SET created_at = now() - interval '60 days' WHERE id = ?")
                .param(leaked.getId())
                .update();

        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].stale", leaked.getId()).value(true))
                .andExpect(jsonPath("$[?(@.id == '%s')].owner", leaked.getId()).value(victim.username()));

        mvc.perform(delete("/api/v1/admin/tokens/" + leaked.getId()).header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());

        assertThat(tokens.authenticate(victim.plaintext())).isNull();
        assertThat(tokens.listFor(victim.userId()).getFirst().getRevokedAt()).isNotNull();
        assertThat(auditEvents.findAll().stream()
                        .filter(e -> "TOKEN_REVOKE".equals(e.getAction()))
                        .anyMatch(e -> e.getParams().contains(victim.username())))
                .isTrue();
    }

    @Test
    void aKeyCannotMintRotateOrListKeys() throws Exception {
        McpFixture.Key key = key(Set.of(Permissions.CLUSTER_READ));
        var own = tokens.listFor(key.userId()).getFirst();

        mvc.perform(post("/api/v1/tokens")
                        .header("Authorization", key.bearer())
                        .contentType("application/json")
                        .content("{\"name\":\"escape\",\"expiresAt\":\""
                                + Instant.now().plusSeconds(3600)
                                + "\",\"grants\":[{\"action\":\"cluster:read\",\"scopeType\":\"GLOBAL\"}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tokens/" + own.getId() + "/rotate").header("Authorization", key.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tokens").header("Authorization", key.bearer())).andExpect(status().isForbidden());
        assertThat(tokens.listFor(key.userId())).hasSize(1);
    }

    @Test
    void theInventoryIsRefusedWithoutThePermission() throws Exception {
        McpFixture.Key plain = key(Set.of(Permissions.CLUSTER_READ));

        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", plain.bearer()))
                .andExpect(status().isForbidden());
    }

    private McpFixture.Key key(Set<String> permissions) {
        return McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.GLOBAL, null, permissions);
    }

    private static void asAdmin(Runnable action) {
        StudioPrincipal admin = new StudioPrincipal(
                null,
                "test-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
