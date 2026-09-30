package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.security.PasswordPolicyException;
import io.github.sudoitir.artemisstudio.kernel.security.PasswordRules;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * What a new local password must satisfy (ADR-0143): a minimum length, bcrypt's 72-byte ceiling,
 * not the username, and not a common or breached password. Length and breaches matter more than
 * composition rules (NIST SP 800-63B). Runs before the password is encoded.
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicy implements PasswordRules {

    /** bcrypt ignores everything past this many bytes, and Spring's encoder refuses longer input. */
    static final int MAX_BYTES = 72;

    private final SettingsService settings;
    private final BreachLookup breachLookup;

    @Override
    public void check(String username, String password) {
        int minLength = settings.intValue(IdentityLocalSettings.PASSWORD_MIN_LENGTH);
        if (password.codePointCount(0, password.length()) < minLength) {
            throw new PasswordPolicyException("Use at least " + minLength + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new PasswordPolicyException(
                    "Use at most " + MAX_BYTES + " bytes; some characters take more than one.");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new PasswordPolicyException("The password must not be your username.");
        }
        if (Common.PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new PasswordPolicyException("That is one of the most commonly used passwords. Choose another.");
        }
        if (breachLookup.isBreached(password)) {
            throw new PasswordPolicyException("That password has appeared in a known data breach. Choose another.");
        }
    }

    /** The offline list, read once on first use. */
    private static final class Common {
        static final Set<String> PASSWORDS = load();

        private static Set<String> load() {
            try (var in = new GZIPInputStream(
                            new ClassPathResource("identitylocal/breached-passwords.txt.gz").getInputStream());
                    var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(line -> line.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
