package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.MfaStatusView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing.Entry;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/** The status answers for every signed-in account, and says whether it is one this section is about. */
class MfaStatusTest {

    private final UserAccounts accounts = mock(UserAccounts.class);
    private final IdentityProviderListing providers = () -> List.of(
            new Entry("local", "CREDENTIAL", "Password", null),
            new Entry("acme:corp", "CREDENTIAL", "Corporate directory", null),
            new Entry("oidc-corp", "REDIRECT", "Corporate SSO", "/oauth2/authorization/corp"));
    private final MfaEnrolment enrolment = new MfaEnrolment(
            mock(TotpStore.class),
            mock(Passkeys.class),
            mock(RecoveryCodes.class),
            mock(TrustedDevices.class),
            new SecondFactorService(
                    mock(TotpStore.class),
                    mock(Passkeys.class),
                    mock(RecoveryCodes.class),
                    mock(TrustedDevices.class),
                    accounts,
                    listing(providers),
                    mock(AuditService.class),
                    mock(ActorResolver.class)),
            accounts,
            mock(SessionAuthentication.class),
            mock(AuditService.class),
            mock(ActorResolver.class));
    private final UUID id = UUID.randomUUID();

    private MfaStatusView statusOf(String providerId) {
        when(accounts.byId(id)).thenReturn(Optional.of(new Account(id, "u", null, false, false, providerId, null)));
        return enrolment.status(new StudioPrincipal(id, "u", Set.of(), false), new MockHttpServletRequest());
    }

    @Test
    void aLocalAccountIsAPasswordAccount() {
        assertThat(statusOf("local").passwordAccount()).isTrue();
    }

    @Test
    void aPluginsSignInAccountIsAPasswordAccount() {
        assertThat(statusOf("acme:corp").passwordAccount()).isTrue();
    }

    @Test
    void aSingleSignOnAccountGetsAnAnswerAndIsNotAPasswordAccount() {
        assertThat(statusOf("oidc-corp").passwordAccount()).isFalse();
    }

    private static ObjectProvider<IdentityProviderListing> listing(IdentityProviderListing listing) {
        return new StaticListableBeanFactory(Map.of("listing", listing)).getBeanProvider(IdentityProviderListing.class);
    }
}
