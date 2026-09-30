package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The status answers for every signed-in account, and says whether it is one this section is about. */
class MfaStatusTest {

    private final UserAccounts accounts = mock(UserAccounts.class);
    private final MfaEnrolment enrolment = new MfaEnrolment(
            mock(TotpStore.class),
            mock(Passkeys.class),
            mock(RecoveryCodes.class),
            mock(TrustedDevices.class),
            mock(SecondFactorService.class),
            accounts,
            mock(SessionAuthentication.class),
            mock(AuditService.class),
            mock(ActorResolver.class));
    private final UUID id = UUID.randomUUID();

    private boolean localFor(String providerId) {
        when(accounts.byId(id)).thenReturn(Optional.of(new Account(id, "u", null, false, false, providerId, null)));
        return enrolment
                .status(new StudioPrincipal(id, "u", Set.of(), false), new MockHttpServletRequest())
                .local();
    }

    @Test
    void aLocalAccountIsLocal() {
        assertThat(localFor("local")).isTrue();
    }

    @Test
    void aSingleSignOnAccountGetsAnAnswerAndIsNotLocal() {
        assertThat(localFor("oidc-corp")).isFalse();
    }
}
