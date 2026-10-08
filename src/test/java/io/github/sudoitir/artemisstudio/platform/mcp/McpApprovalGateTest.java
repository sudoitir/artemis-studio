package io.github.sudoitir.artemisstudio.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.feature.messages.MessageService;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalReasonRequiredException;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.artemisstudio.kernel.gate.AuthKind;
import io.github.sudoitir.artemisstudio.kernel.gate.GateContext;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationDeniedException;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationHeldException;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The approval gate seen from an MCP tool (ADR-0179): every call is an agent's, the reason the agent gives reaches the
 * gate, and a held, denied, unavailable or reasonless operation comes back as text the agent can act on, never as a
 * failure to retry.
 */
class McpApprovalGateTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @MockitoBean
    MessageService messages;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    private MockMvc mvc;
    private McpFixture.Key key;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));
    }

    private JsonNode purge(Map<String, Object> extra) throws Exception {
        Map<String, Object> arguments = new java.util.HashMap<>(Map.of(
                "clusterId",
                CLUSTER.toString(),
                "queue",
                "orders",
                "action",
                "purge",
                "dryRun",
                false,
                "confirm",
                "orders"));
        arguments.putAll(extra);
        return McpFixture.callTool(mvc, key, "message_action", arguments);
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asString();
    }

    @Test
    void everyCallIsAnAgentsAndCarriesTheReasonItGave() throws Exception {
        AtomicReference<AuthKind> origin = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>();
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenAnswer(call -> {
                    origin.set(GateContext.ORIGIN.isBound() ? GateContext.ORIGIN.get() : null);
                    reason.set(GateContext.REASON.isBound() ? GateContext.REASON.get() : null);
                    return new Attempt.Ok<>(new MessageService.Outcome.Affected(3, UUID.randomUUID()));
                });

        JsonNode response = purge(Map.of("approvalReason", "Poison messages"));

        assertThat(response.path("result").path("isError").asBoolean())
                .describedAs("%s", response)
                .isFalse();
        assertThat(origin).hasValue(AuthKind.AGENT);
        assertThat(reason).hasValue("Poison messages");
    }

    @Test
    void withNoReasonNoneIsBound() throws Exception {
        AtomicReference<Boolean> bound = new AtomicReference<>();
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenAnswer(call -> {
                    bound.set(GateContext.REASON.isBound());
                    return new Attempt.Ok<>(new MessageService.Outcome.Affected(3, UUID.randomUUID()));
                });

        purge(Map.of());

        assertThat(bound).hasValue(false);
    }

    @Test
    void aHeldOperationIsTextSayingWhoMustApproveAndNotAnError() throws Exception {
        UUID heldId = UUID.randomUUID();
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new OperationHeldException(heldId, "Purge queue orders on prod-eu", Instant.now()));

        JsonNode response = purge(Map.of());

        assertThat(text(response))
                .isEqualTo("Held for approval: Purge queue orders on prod-eu (request " + heldId
                        + "). A second person must approve it in Studio: /approvals/" + heldId);
    }

    @Test
    void aDeniedUnavailableOrReasonlessOperationIsAToolErrorWithItsReason() throws Exception {
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new OperationDeniedException("Frozen until Monday"));
        JsonNode denied = purge(Map.of());
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(denied)).isEqualTo("Frozen until Monday");

        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new ApprovalUnavailableException("The approval provider did not answer"));
        assertThat(text(purge(Map.of()))).isEqualTo("The approval provider did not answer");

        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new ApprovalReasonRequiredException("Give a reason with approvalReason"));
        JsonNode reasonless = purge(Map.of());
        assertThat(reasonless.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(reasonless)).isEqualTo("Give a reason with approvalReason");
    }

    @Test
    void aBlankReasonIsNoReason() {
        assertThatThrownBy(() -> McpErrors.guard("  ", () -> {
                    assertThat(GateContext.REASON.isBound()).isFalse();
                    throw new IllegalArgumentException("probe");
                }))
                .isInstanceOf(io.modelcontextprotocol.spec.McpError.class);
    }
}
