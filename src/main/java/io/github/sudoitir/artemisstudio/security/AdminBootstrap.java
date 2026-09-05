package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a single administrator account on first boot, when no user account
 * exists yet (identity-and-sessions spec). The generated password is disclosed
 * exactly once, in the startup log, and the account is forced to change it
 * before doing anything else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    private static final String ADMIN_USERNAME = "admin";
    private static final int PASSWORD_BYTES = 18; // -> 24-char base64url

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapIfEmpty() {
        if (users.count() > 0) {
            return;
        }
        String password = generatePassword();
        AppUserEntity admin = AppUserEntity.local(ADMIN_USERNAME, null, passwordEncoder.encode(password));
        admin.setMustChangePassword(true);
        admin = users.save(admin);

        var adminRole = roles.findByName("ADMIN")
                .orElseThrow(() ->
                        new IllegalStateException("ADMIN role missing - did changeset 014-builtin-role-seed run?"));
        userRoles.save(new UserRoleEntity(admin.getId(), adminRole.getId(), "GLOBAL", ScopeIds.GLOBAL));

        log.warn("""

                ================================================================
                 Artemis Studio: no user accounts found. Created administrator:

                   username: {}
                   password: {}

                 This password is shown ONLY ONCE. Log in and change it now.
                ================================================================""", ADMIN_USERNAME, password);
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
