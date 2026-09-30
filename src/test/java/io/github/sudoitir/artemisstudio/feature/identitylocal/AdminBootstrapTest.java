package io.github.sudoitir.artemisstudio.feature.identitylocal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Task 3.13: bootstraps exactly once, when no account exists, never again once one does. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AdminBootstrapTest {

    @Mock
    UserAccounts accounts;

    @Mock
    PasswordEncoder passwordEncoder;

    @Test
    void createsTheFirstAdministratorWithAGeneratedPasswordWhenNoAccountExists() {
        when(accounts.anyExist()).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hashed");
        when(accounts.createFirstAdministrator("admin", "{bcrypt}hashed")).thenReturn(true);

        new AdminBootstrap(accounts, passwordEncoder).bootstrapIfEmpty();

        verify(accounts).createFirstAdministrator("admin", "{bcrypt}hashed");
    }

    @Test
    void printsTheOneTimePasswordUnredacted(CapturedOutput output) {
        when(accounts.anyExist()).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hashed");
        when(accounts.createFirstAdministrator("admin", "{bcrypt}hashed")).thenReturn(true);

        new AdminBootstrap(accounts, passwordEncoder).bootstrapIfEmpty();

        ArgumentCaptor<CharSequence> password = ArgumentCaptor.forClass(CharSequence.class);
        verify(passwordEncoder).encode(password.capture());
        assertThat(output.getOut())
                .contains("Created administrator")
                .contains("password: " + password.getValue())
                .doesNotContain("[redacted]");
    }

    @Test
    void doesNothingWhenAnAccountAlreadyExists() {
        when(accounts.anyExist()).thenReturn(true);

        new AdminBootstrap(accounts, passwordEncoder).bootstrapIfEmpty();

        verify(accounts, never()).createFirstAdministrator(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }
}
