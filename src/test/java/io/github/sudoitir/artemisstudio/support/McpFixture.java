package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.Grant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-side plumbing for the MCP surface: mint a real API key with real grants,
 * and speak JSON-RPC to {@code /mcp} through the real filter chain.
 *
 * <p>The key is minted through {@link ApiTokenService}, not stubbed, because the
 * property under test in every MCP test is that the token path — prefix lookup,
 * hash compare, grant intersection with the owner's live grants — is the same one
 * the REST API uses (ADR-0046). A fake principal would prove none of it.
 */
public final class McpFixture {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpFixture() {}

    /** A user holding {@code permissions} at {@code scope}, and an API key narrowed to exactly the same. */
    public record Key(UUID userId, String username, String plaintext) {

        public String bearer() {
            return "Bearer " + plaintext;
        }
    }

    public static Key mintKey(
            AppUserRepository users,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            UserRoleRepository userRoles,
            ApiTokenService tokens,
            Grant.ScopeType scopeType,
            UUID scopeId,
            Set<String> permissions) {
        // user_role.scope_id and api_token_grant.scope_id are NOT NULL; a GLOBAL row
        // carries the nil UUID sentinel (014-identity.sql), not null. The token/owner
        // grant intersection compares scope ids for equality, so both sides must use it.
        UUID scope = scopeType == Grant.ScopeType.GLOBAL
                ? io.github.sudoitir.artemisstudio.security.ScopeIds.GLOBAL
                : scopeId;
        String username = "mcp-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);

        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), action));
        }
        userRoles.save(new UserRoleEntity(user.getId(), role.getId(), scopeType.name(), scope));

        var minted = tokens.mint(
                user.getId(), "mcp-test-key", null, List.of(new Grant(scopeType, scope, Set.copyOf(permissions))));
        return new Key(user.getId(), username, minted.plaintext());
    }

    /** One JSON-RPC round trip against {@code /mcp}. Returns the parsed envelope. */
    public static JsonNode rpc(MockMvc mvc, Key key, String method, Object params) throws Exception {
        String body = JSON.writeValueAsString(
                params == null
                        ? java.util.Map.of("jsonrpc", "2.0", "id", 1, "method", method)
                        : java.util.Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        String raw = mvc.perform(MockMvcRequestBuilders.post("/mcp")
                        .header("Authorization", key.bearer())
                        .contentType("application/json")
                        .accept("application/json", "text/event-stream")
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(raw);
    }

    /** {@code tools/call}, returning the {@code result} object (which may carry {@code isError}). */
    public static JsonNode callTool(MockMvc mvc, Key key, String tool, java.util.Map<String, Object> arguments)
            throws Exception {
        return rpc(mvc, key, "tools/call", java.util.Map.of("name", tool, "arguments", arguments));
    }
}
