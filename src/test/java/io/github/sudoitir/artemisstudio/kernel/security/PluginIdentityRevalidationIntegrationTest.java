package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatus;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInProbe;
import io.github.sudoitir.artemisstudio.support.Browser;
import io.github.sudoitir.artemisstudio.support.SignInPluginIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Studio asks a plugin's sign-in provider which of its users it no longer vouches for, and takes
 * their access away (ADR-0156): sessions, API tokens and the audit row, with the account left alone.
 */
class PluginIdentityRevalidationIntegrationTest extends SignInPluginIntegrationTest {

    @Autowired
    PluginIdentityRevalidation revalidation;

    @Autowired
    JobStatuses jobStatuses;

    private String bearer(Browser browser) throws Exception {
        return JsonPath.read(browser.post("/api/v1/tokens", mintBody("ci")).body(), "$.value");
    }

    @Test
    void aRevokedUsersSessionAndTokenStopWorkingAndItIsAudited() throws Exception {
        String id = installSignInPlugin(true);
        String provider = provider(id);
        var directory = SignInProbe.of(provider)
                .add("uid=kim", "kim", DIRECTORY_PASSWORD, "eng")
                .add("uid=lee", "lee", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        Browser kim = signedInVia(provider, "kim", DIRECTORY_PASSWORD);
        String kimToken = bearer(kim);
        Browser lee = signedInVia(provider, "lee", DIRECTORY_PASSWORD);
        assertThat(kim.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(browser()
                        .send("GET", ME, null, "Authorization", "Bearer " + kimToken)
                        .statusCode())
                .isEqualTo(200);

        assertThat(jobStatuses.all()).extracting(JobStatus::id).contains("plugin-identity-revalidation");
        directory.revoked.add("uid=kim");
        revalidation.run();

        assertThat(directory.asked).singleElement().isEqualTo(Set.of("uid=kim", "uid=lee"));
        assertThat(kim.status("GET", CONSOLE)).isEqualTo(401);
        assertThat(browser()
                        .send("GET", ME, null, "Authorization", "Bearer " + kimToken)
                        .statusCode())
                .isEqualTo(401);
        assertThat(lee.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(audited("IDENTITY_REVOKED", "kim")).isEqualTo(1);
        assertThat(audited("IDENTITY_REVOKED", "lee")).isZero();
        assertThat(account(provider, "uid=kim").isDisabled()).isFalse();
    }

    @Test
    void aSubjectTheProviderWasNotAskedAboutIsIgnored() throws Exception {
        String id = installSignInPlugin(true);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=max", "max", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        Browser max = signedInVia(provider, "max", DIRECTORY_PASSWORD);

        directory.revoked.add("uid=stranger");
        revalidation.run();

        assertThat(max.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(audited("IDENTITY_REVOKED", "uid=stranger")).isZero();
    }

    @Test
    void aRestoredUserSignsInAgainIntoTheSameAccount() throws Exception {
        String id = installSignInPlugin(true);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=ned", "ned", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        Browser first = signedInVia(provider, "ned", DIRECTORY_PASSWORD);
        var accountId = account(provider, "uid=ned").getId();

        directory.revoked.add("uid=ned");
        revalidation.run();
        assertThat(first.status("GET", CONSOLE)).isEqualTo(401);
        assertThat(loginVia(browser(), provider, "ned", DIRECTORY_PASSWORD).statusCode())
                .isEqualTo(401);

        directory.revoked.clear();
        Browser again = signedInVia(provider, "ned", DIRECTORY_PASSWORD);

        assertThat(again.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(account(provider, "uid=ned").getId()).isEqualTo(accountId);
    }

    @Test
    void aProviderWithoutTheMethodChangesNothing() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=oli", "oli", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        Browser oli = signedInVia(provider, "oli", DIRECTORY_PASSWORD);
        // Even a directory that has lost the user: this bean never says so.
        directory.people.remove("oli");

        revalidation.run();

        assertThat(oli.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(audited("IDENTITY_REVOKED", "oli")).isZero();
    }
}
