package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.AccountLockout;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.PersonalTokens;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Break-glass (ADR-0142, D10): the operator names one local account in
 * {@code artemis-studio.identity-local.recover} and restarts Studio, and that account is unlocked,
 * loses its second factors, recovery codes and trusted devices, has its API tokens revoked, must change its password at the next
 * sign-in and has its sessions ended. It is for the sole administrator who lost both a device and the
 * recovery codes, and it needs access to the deployment, which is what makes it safe. It logs a
 * warning to remove the property, because the next restart would recover the account again, and
 * audits what it did.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountRecovery {

    private final IdentityLocalProperties properties;
    private final UserAccounts accounts;
    private final AccountLockout lockout;
    private final SessionAuthentication sessions;
    private final Optional<SecondFactors> factors;
    private final Optional<PersonalTokens> personalTokens;
    private final AuditService audit;
    private final ActorResolver actors;
    private final TransactionTemplate transaction;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        recover(properties.recover());
    }

    /** Recover the local account of this name; a name that is blank, unknown or not local changes nothing and is reported. */
    void recover(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        Optional<UserAccounts.Account> account = accounts.byUsername(username.strip())
                .filter(a -> IdentityLocalModule.PROVIDER_ID.equals(a.providerId()));
        if (account.isEmpty()) {
            log.error(
                    "artemis-studio.identity-local.recover names '{}', which is no local account. Nothing was recovered."
                            + " Check the spelling, or remove the property.",
                    username);
            return;
        }
        UserAccounts.Account user = account.get();
        // All or nothing, and the sessions end only once it commits.
        transaction.executeWithoutResult(status -> {
            lockout.unlock(user.id(), user.username());
            factors.ifPresent(f -> f.reset(user.id()));
            personalTokens.ifPresent(t -> t.revokeAllOf(user.id()));
            accounts.requirePasswordChange(user.id());
            sessions.endSessionsOf(user.username());
            audit.succeed(
                    audit.begin(actors.resolve(), "ACCOUNT_RECOVER", "user", user.username(), null, null, null, false),
                    1);
        });
        log.warn(
                "Account '{}' was recovered at startup: unlocked, its second factors, recovery codes and trusted"
                        + " devices removed, its API tokens revoked, its sessions ended, and a password change required at its next sign-in."
                        + " Now remove artemis-studio.identity-local.recover (ARTEMIS_STUDIO_IDENTITY_LOCAL_RECOVER),"
                        + " or the next restart recovers it again.",
                user.username());
    }
}
