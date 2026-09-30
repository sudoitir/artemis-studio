package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates a single administrator account on first boot, when no user account
 * exists yet (identity-and-sessions spec). The generated password is disclosed
 * exactly once, on standard output, and the account is forced to change it
 * before doing anything else. It is printed rather than logged on purpose: the
 * log redacts every credential (ADR-0133), and this one disclosure is the point.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    private static final String ADMIN_USERNAME = "admin";
    private static final int PASSWORD_BYTES = 18; // -> 24-char base64url

    private final UserAccounts accounts;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapIfEmpty() {
        if (accounts.anyExist()) {
            return;
        }
        String password = generatePassword();
        if (!accounts.createFirstAdministrator(ADMIN_USERNAME, passwordEncoder.encode(password))) {
            return;
        }
        // The one deliberate disclosure, so it bypasses the redacting log (ADR-0133).
        System.out.printf("""

                ================================================================
                 Artemis Studio: no user accounts found. Created administrator:

                   username: %s
                   password: %s

                 This password is shown ONLY ONCE. Log in and change it now.
                ================================================================
                %n""", ADMIN_USERNAME, password);
        System.out.flush();
        log.warn("No user accounts found; created the initial administrator (its one-time password is printed above)");
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
