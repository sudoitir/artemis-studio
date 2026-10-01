package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInProbe;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.support.Browser;
import io.github.sudoitir.artemisstudio.support.SignInPluginIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Studio's second factor covers every account that signs in with a password, a plugin's included
 * (ADR-0153), and still leaves alone the users of a provider that does its own.
 */
class PluginSignInSecondFactorIntegrationTest extends SignInPluginIntegrationTest {

    @Autowired
    SecondFactors factors;

    private void makeRoleRequireMfa(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow();
        role.setRequiresMfa(true);
        roles.save(role);
    }

    @Test
    void aPluginUserWithARequiredRoleEnrolsAndThenSignsInWithPasswordAndCode() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=pia", "pia", DIRECTORY_PASSWORD, "ops");
        makeRoleRequireMfa(mapGroup(provider, "ops", Permissions.CLUSTER_READ));

        Browser first = browser();
        HttpResponse<String> signIn = loginVia(first, provider, "pia", DIRECTORY_PASSWORD);
        assertThat((String) JsonPath.read(signIn.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.secondFactorEnrolmentRequired"))
                .isTrue();
        assertThat(first.status("GET", CONSOLE)).isEqualTo(423);

        var status = first.send("GET", "/api/v1/auth/mfa", null);
        assertThat((Boolean) JsonPath.read(status.body(), "$.passwordAccount")).isTrue();
        assertThat((Boolean) JsonPath.read(status.body(), "$.required")).isTrue();
        Enrolled enrolled = enrol(first);

        Browser later = browser();
        HttpResponse<String> password = loginVia(later, provider, "pia", DIRECTORY_PASSWORD);
        assertThat((String) JsonPath.read(password.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        String username = account(provider, "uid=pia").getUsername();
        var code = secondFactor(later, "totpCode", freshCode(username, enrolled));
        assertThat((String) JsonPath.read(code.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat(later.status("GET", CONSOLE)).isEqualTo(200);
    }

    @Test
    void aUserOfAProviderThatDoesItsOwnMfaIsNotChallenged() {
        UUID role = roles.save(requiringMfa(new RoleEntity("sso-mfa-" + UUID.randomUUID(), false)))
                .getId();
        AppUserEntity user = users.save(AppUserEntity.external("oidc-corp", "sub-1", "oidc-user", null));
        userRoles.save(new UserRoleEntity(user.getId(), role, "GLOBAL", ScopeIds.GLOBAL));

        assertThat(factors.required(user.getId())).isFalse();
        assertThat(factors.enrolmentRequired(user.getId())).isFalse();
        users.delete(user);
    }

    private static RoleEntity requiringMfa(RoleEntity role) {
        role.setRequiresMfa(true);
        return role;
    }
}
