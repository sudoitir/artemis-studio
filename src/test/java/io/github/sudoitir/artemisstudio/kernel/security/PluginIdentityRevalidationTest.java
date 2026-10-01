package io.github.sudoitir.artemisstudio.kernel.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.internal.PluginSignIn;
import io.github.sudoitir.artemisstudio.kernel.security.internal.SessionTerminator;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository.EnabledAccount;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** One account whose revocation fails must not stop the pass for the other accounts (ADR-0156). */
class PluginIdentityRevalidationTest {

    @Test
    void aFailedRevocationDoesNotStopTheRestOfThePass() {
        PluginSignIn signIn = mock(PluginSignIn.class);
        AppUserRepository users = mock(AppUserRepository.class);
        SessionTerminator sessions = mock(SessionTerminator.class);
        AdministrationAudit audit = mock(AdministrationAudit.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        PluginSignIn.Provider provider = mock(PluginSignIn.Provider.class);
        when(provider.id()).thenReturn("ldap");
        when(provider.noLongerValid(any())).thenReturn(Set.of("s1", "s2"));
        when(signIn.verified()).thenReturn(List.of(provider));
        EnabledAccount kim = account("kim", "s1");
        EnabledAccount lee = account("lee", "s2");
        when(users.findByProviderIdAndDisabledFalse("ldap")).thenReturn(List.of(kim, lee));
        doThrow(new IllegalStateException("session store down")).when(sessions).endSessionsOf(List.of("kim"));

        new PluginIdentityRevalidation(
                        signIn,
                        users,
                        sessions,
                        audit,
                        Optional.empty(),
                        Optional.empty(),
                        new TransactionTemplate(manager))
                .run();

        verify(sessions).endSessionsOf(List.of("lee"));
        verify(audit).changed(eq("IDENTITY_REVOKED"), eq("user"), eq("lee"), any());
    }

    private static EnabledAccount account(String username, String subject) {
        EnabledAccount account = mock(EnabledAccount.class);
        when(account.getId()).thenReturn(UUID.randomUUID());
        when(account.getUsername()).thenReturn(username);
        when(account.getExternalSubject()).thenReturn(subject);
        return account;
    }
}
