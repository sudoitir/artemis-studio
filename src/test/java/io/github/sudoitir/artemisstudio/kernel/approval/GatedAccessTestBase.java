package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.approval.GateTestKit.FakeHandle;
import io.github.sudoitir.artemisstudio.kernel.approval.GateTestKit.TestProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationHeldException;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import io.github.sudoitir.artemisstudio.kernel.gate.Vote;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.AccessChanges;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.IdentityProviderCatalog;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the tests of gated services share: an approval provider that holds every request (armed on demand, so a test
 * can first build its fixtures through the same services without being held), people who request and approve, and
 * the steps from a held request to its replay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GateTestKit.class)
abstract class GatedAccessTestBase extends PostgresIntegrationTest {

    static final String PROVIDER = "gate-access-provider";
    static final String PASSWORD = "correct-horse-battery";

    @Autowired
    TestProvider provider;

    @Autowired
    Approvals approvals;

    @Autowired
    ApprovalProviderRegistry providers;

    @Autowired
    PluginStore pluginStore;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginInstallers installers;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PermissionHolders holders;

    @MockitoSpyBean
    IdentityProviderCatalog catalog;

    @MockitoSpyBean
    SessionAuthentication sessions;

    private FakeHandle handle;

    record Person(UUID id, String username) {}

    @BeforeEach
    void startUnarmed() {
        provider.reset();
        jdbc.update("UPDATE plugin_install SET approval_provider = false");
        installs.findById(PROVIDER).ifPresent(installs::delete);
    }

    @AfterEach
    void disarm() {
        if (handle != null) {
            providers.detach(handle);
            handle = null;
        }
        installs.findById(PROVIDER).ifPresent(installs::delete);
        SecurityContextHolder.clearContext();
    }

    /** Installs the provider plugin, whose approvers hold {@link GateTestKit#APPROVER_PERMISSION}, so the gate holds. */
    void arm() {
        String sha = pluginStore.put(("gate-access-" + UUID.randomUUID()).getBytes());
        String descriptor = storedDescriptor(
                PROVIDER, Map.of("approvalProvider", Map.of("approverPermission", GateTestKit.APPROVER_PERMISSION)));
        PluginInstallEntity entity = new PluginInstallEntity(PROVIDER, "1.0.0", "Acme", sha, "tester", descriptor);
        entity.approvalProvider(true);
        installs.save(entity);
        jdbc.update("UPDATE plugin_install SET status = 'active' WHERE id = ?", PROVIDER);
        handle = new FakeHandle(PROVIDER, provider);
        providers.attach(handle);
    }

    /** A readable stored {@code plugin.json} for {@code id}, with {@code fields} on top of the defaults. */
    static String storedDescriptor(String id, Map<String, Object> fields) {
        Map<String, Object> descriptor = PluginJarBuilder.defaultDescriptor(id);
        descriptor.putAll(fields);
        return JsonMapper.builder().build().writeValueAsString(descriptor);
    }

    Person newUser(String... permissions) {
        String username = "gate-" + UUID.randomUUID().toString().substring(0, 12);
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        users.save(user);
        if (permissions.length > 0) {
            RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
            for (String permission : permissions) {
                rolePermissions.save(new RolePermissionEntity(role.getId(), permission));
            }
            userRoles.save(
                    new UserRoleEntity(user.getId(), role.getId(), Grant.ScopeType.GLOBAL.name(), ScopeIds.GLOBAL));
            // As an administrator would, not as whoever this thread has signed in: that would log an access change
            // by them, which the approver rules count against their requests.
            var signedIn = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.clearContext();
            accessChanges.changedFor(user.getId());
            if (signedIn != null) {
                SecurityContextHolder.getContext().setAuthentication(signedIn);
            }
        }
        return new Person(user.getId(), username);
    }

    /** Someone who administers users and, for the plugin tests, may install plugins. */
    Person requester() {
        Person person = newUser(GateTestKit.APPROVER_PERMISSION);
        installers.grant(person.id(), "tester");
        return person;
    }

    Person approver() {
        return newUser(GateTestKit.APPROVER_PERMISSION);
    }

    /** Signs {@code person} in on this thread, as a browser session with a fresh sign-in. */
    void signIn(Person person) {
        StudioPrincipal principal =
                new StudioPrincipal(person.id(), person.username(), grants.loadFor(person.id()), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        Instant now = Instant.now();
        doReturn(Optional.of(new SessionFacts(now, null, null, now, "127.0.0.1", "test")))
                .when(sessions)
                .current();
    }

    void approversAre(Person... people) {
        when(holders.holders(any(), eq(GateTestKit.APPROVER_PERMISSION), anyInt()))
                .thenReturn(Arrays.stream(people).map(Person::id).toList());
    }

    /** Runs the request as {@code person} and returns the hold it ends in. */
    UUID hold(Person person, Runnable request) {
        signIn(person);
        try {
            request.run();
        } catch (OperationHeldException e) {
            return e.heldId();
        }
        throw new AssertionError("expected the request to be held");
    }

    void approve(Person approver, UUID id) {
        signIn(approver);
        Approvals.Detail detail = approvals.get(id);
        approvals.decide(id, Vote.APPROVE, null, detail.view().paramsHash(), detail.version());
    }

    HeldState stateOf(UUID id) {
        return HeldState.valueOf(
                jdbc.queryForObject("SELECT state FROM held_operation WHERE id = ?", String.class, id));
    }

    void awaitState(UUID id, HeldState expected) {
        await("held operation " + id + " becomes " + expected)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> stateOf(id) == expected);
    }

    /** The provider's last request of this type, as the gate showed it to the provider. */
    io.github.sudoitir.artemisstudio.kernel.gate.GateRequest lastRequest(String type) {
        return provider.requests.stream()
                .filter(request -> request.type().equals(type))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    void assertTraits(String type, Trait... expected) {
        assertThat(lastRequest(type).traits()).containsExactlyInAnyOrder(expected);
    }
}
