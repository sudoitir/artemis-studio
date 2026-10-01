package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing.Entry;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Who is required to hold a second factor (ADR-0143, ADR-0153): password accounts holding a role that requires one, nobody else. */
class SecondFactorServiceTest {

    private final UserAccounts accounts = mock(UserAccounts.class);
    private final IdentityProviderListing providers = () -> List.of(
            new Entry("local", "CREDENTIAL", "Password", null),
            new Entry("acme:corp", "CREDENTIAL", "Corporate directory", null),
            new Entry("oidc-corp", "REDIRECT", "Corporate SSO", "/oauth2/authorization/corp"));
    private final SecondFactorService factors = new SecondFactorService(
            mock(TotpStore.class),
            mock(Passkeys.class),
            mock(RecoveryCodes.class),
            mock(TrustedDevices.class),
            accounts,
            listing(providers),
            mock(AuditService.class),
            mock(ActorResolver.class));
    private final UUID id = UUID.randomUUID();

    private void account(String providerId, boolean holdsMfaRole) {
        when(accounts.byId(id)).thenReturn(Optional.of(new Account(id, "u", null, false, false, providerId, null)));
        when(accounts.holdsMfaRole(id)).thenReturn(holdsMfaRole);
    }

    @Test
    void aLocalAccountHoldingARoleThatRequiresMfaIsRequired() {
        account("local", true);

        assertThat(factors.required(id)).isTrue();
    }

    @Test
    void aLocalAccountWithoutSuchARoleIsNot() {
        account("local", false);

        assertThat(factors.required(id)).isFalse();
    }

    @Test
    void aPluginsSignInAccountHoldingARoleThatRequiresMfaIsRequired() {
        account("acme:corp", true);

        assertThat(factors.required(id)).isTrue();
    }

    @Test
    void aSingleSignOnAccountIsNeverRequiredBecauseItsProviderDoesMfa() {
        account("oidc-corp", true);

        assertThat(factors.required(id)).isFalse();
    }

    @Test
    void anUnknownAccountIsNot() {
        when(accounts.byId(id)).thenReturn(Optional.empty());

        assertThat(factors.required(id)).isFalse();
    }

    private static ObjectProvider<IdentityProviderListing> listing(IdentityProviderListing listing) {
        return new StaticListableBeanFactory(Map.of("listing", listing)).getBeanProvider(IdentityProviderListing.class);
    }
}
