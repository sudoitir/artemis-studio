package io.github.sudoitir.artemisstudio.feature.identitylocal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.PasswordPolicyException;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    SettingsService settings = mock(SettingsService.class);
    BreachLookup breachLookup = mock(BreachLookup.class);
    PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        when(settings.intValue(IdentityLocalSettings.PASSWORD_MIN_LENGTH)).thenReturn(12);
        policy = new PasswordPolicy(settings, breachLookup);
    }

    @Test
    void acceptsALongPassphrase() {
        assertThatCode(() -> policy.check("operator", "correct horse battery staple"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAPasswordShorterThanTheSetting() {
        assertThatThrownBy(() -> policy.check("operator", "eleven-char"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessage("Use at least 12 characters.");
    }

    @Test
    void followsTheSettingAtRuntime() {
        when(settings.intValue(IdentityLocalSettings.PASSWORD_MIN_LENGTH)).thenReturn(20);

        assertThatThrownBy(() -> policy.check("operator", "correct horse batt"))
                .hasMessage("Use at least 20 characters.");
    }

    @Test
    void refusesMoreThan72Bytes() {
        assertThatThrownBy(() -> policy.check("operator", "a".repeat(73)))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("72 bytes");
    }

    @Test
    void countsBytesNotCharacters() {
        // 40 characters, 80 bytes.
        assertThatThrownBy(() -> policy.check("operator", "é".repeat(40))).hasMessageContaining("72 bytes");
        assertThatCode(() -> policy.check("operator", "é".repeat(36))).doesNotThrowAnyException();
    }

    @Test
    void refusesTheUsernameIgnoringCase() {
        assertThatThrownBy(() -> policy.check("Studio-Admin-1", "studio-admin-1"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("username");
    }

    @Test
    void refusesACommonPasswordInAnyCase() {
        assertThatThrownBy(() -> policy.check("operator", "qwerty123456"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("commonly used");
        assertThatThrownBy(() -> policy.check("operator", "Qwerty123456")).isInstanceOf(PasswordPolicyException.class);
    }

    @Test
    void asksTheOnlineLookupOnlyAfterTheOfflineChecksPass() {
        assertThatThrownBy(() -> policy.check("operator", "qwerty123456")).isInstanceOf(PasswordPolicyException.class);
        verifyNoInteractions(breachLookup);

        when(breachLookup.isBreached("a long and unusual phrase")).thenReturn(true);
        assertThatThrownBy(() -> policy.check("operator", "a long and unusual phrase"))
                .hasMessageContaining("data breach");
    }
}
