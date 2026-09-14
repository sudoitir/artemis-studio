package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A directory-style credential provider added from test configuration alone signs users in,
 * provisions them by provider and subject, and applies that provider's group mappings, with no
 * kernel edit (task 6.9, ADR-0073).
 */
@Import(ExternalIdentityProviderTest.Directories.class)
class ExternalIdentityProviderTest extends PostgresIntegrationTest {

    /** The groups both fake directories report for their one user. */
    static final AtomicReference<Set<String>> GROUPS = new AtomicReference<>(Set.of());

    /** A directory that knows one user, "dana", under the same subject in every directory. */
    record Directory(String id, IdentityProvisioner provisioner) implements CredentialIdentityProvider {

        @Override
        public String label() {
            return "Directory " + id;
        }

        @Override
        public Optional<StudioPrincipal> authenticate(String username, String password) {
            if (!"dana".equals(username) || !"directory-pass".equals(password)) {
                return Optional.empty();
            }
            return provisioner.provision(new ExternalIdentity(id, "uid=dana", "dana", null, GROUPS.get()));
        }
    }

    @TestConfiguration
    static class Directories {

        @Bean
        IdentityProviders directories(IdentityProvisioner provisioner) {
            return new IdentityProviders() {
                @Override
                public List<IdentityProvider> providers() {
                    return List.of(new Directory("dir-a", provisioner), new Directory("dir-b", provisioner));
                }
            };
        }
    }

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GroupMappingRepository mappings;

    @Autowired
    DefaultRoleRepository defaultRoles;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        GROUPS.set(Set.of("eng"));
    }

    @AfterEach
    void cleanUp() {
        mappings.deleteAll();
        defaultRoles.deleteAll();
        users.findAll().stream()
                .filter(u -> u.getProviderId().startsWith("dir-"))
                .forEach(users::delete);
    }

    private ResultActions login(String provider) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .session(new MockHttpSession())
                .with(csrf())
                .contentType("application/json")
                .content("{\"provider\":\"%s\",\"username\":\"dana\",\"password\":\"directory-pass\"}"
                        .formatted(provider)));
    }

    private UUID role(String name) {
        return roles.findByName(name).map(RoleEntity::getId).orElseThrow();
    }

    private List<String> rolesOf(AppUserEntity user) {
        return userRoles.findByIdUserId(user.getId()).stream()
                .map(ur -> roles.findById(ur.getRoleId()).orElseThrow().getName())
                .toList();
    }

    private AppUserEntity dana(String provider) {
        return users.findByProviderIdAndExternalSubject(provider, "uid=dana").orElseThrow();
    }

    @Test
    void theProvidersAreListedForTheLoginScreen() throws Exception {
        mvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem("dir-a")))
                .andExpect(jsonPath("$[*].id", hasItem("dir-b")));
    }

    @Test
    void aUserMatchingNoMappingIsRefusedWhenTheProviderHasNoDefaultRole() throws Exception {
        login("dir-a").andExpect(status().isUnauthorized());
    }

    @Test
    void groupMappingsAreReappliedAtEverySignIn() throws Exception {
        mappings.save(new GroupMappingEntity("dir-a", "eng", role("OPERATOR"), "GLOBAL", ScopeIds.GLOBAL));
        defaultRoles.save(new DefaultRoleEntity("dir-a", role("VIEWER")));

        login("dir-a").andExpect(status().isOk());
        assertThat(rolesOf(dana("dir-a"))).containsExactly("OPERATOR");

        GROUPS.set(Set.of("support"));
        login("dir-a").andExpect(status().isOk());
        assertThat(rolesOf(dana("dir-a"))).containsExactly("VIEWER");
    }

    @Test
    void theSameSubjectFromTwoProvidersIsTwoAccounts() throws Exception {
        mappings.save(new GroupMappingEntity("dir-b", "eng", role("VIEWER"), "GLOBAL", ScopeIds.GLOBAL));
        defaultRoles.save(new DefaultRoleEntity("dir-a", role("OPERATOR")));

        login("dir-a").andExpect(status().isOk());
        login("dir-b").andExpect(status().isOk());

        assertThat(dana("dir-a").getId()).isNotEqualTo(dana("dir-b").getId());
        assertThat(dana("dir-b").getUsername()).isEqualTo("dana@dir-b");
        assertThat(rolesOf(dana("dir-a"))).containsExactly("OPERATOR");
        assertThat(rolesOf(dana("dir-b"))).containsExactly("VIEWER");
    }
}
