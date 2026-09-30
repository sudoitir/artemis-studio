package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts.Method;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second factors of local accounts as the sign-in path sees them (ADR-0142): TOTP and recovery
 * codes today. Only accounts of the local provider are ever required to hold one; any other provider
 * does its own multi-factor authentication.
 */
@Component
@RequiredArgsConstructor
class SecondFactorService implements SecondFactors {

    private final TotpStore totp;
    private final RecoveryCodes recoveryCodes;
    private final UserAccounts accounts;
    private final AuditService audit;
    private final ActorResolver actors;

    @Override
    public boolean enrolled(UUID userId) {
        return totp.hasActive(userId);
    }

    @Override
    public boolean required(UUID userId) {
        return accounts.byId(userId)
                        .filter(a -> IdentityLocalModule.PROVIDER_ID.equals(a.providerId()))
                        .isPresent()
                && accounts.holdsMfaRole(userId);
    }

    @Override
    public List<Method> methods(UUID userId) {
        List<Method> methods = new ArrayList<>();
        if (totp.hasActive(userId)) {
            methods.add(Method.TOTP);
            if (recoveryCodes.remaining(userId) > 0) {
                methods.add(Method.RECOVERY_CODE);
            }
        }
        return methods;
    }

    @Override
    @Transactional
    public Optional<Method> verify(UUID userId, Proof proof) {
        return switch (proof) {
            case TotpCode t -> verifyTotp(userId, t.code());
            case RecoveryCode r -> verifyRecoveryCode(userId, r.code());
        };
    }

    private Optional<Method> verifyTotp(UUID userId, String code) {
        OptionalLong step = totp.active(userId)
                .map(secret -> Totp.matchingStep(secret, code, Instant.now()))
                .orElse(OptionalLong.empty());
        return step.isPresent() && totp.advance(userId, step.getAsLong()) ? Optional.of(Method.TOTP) : Optional.empty();
    }

    private Optional<Method> verifyRecoveryCode(UUID userId, String code) {
        if (!recoveryCodes.spend(userId, code)) {
            return Optional.empty();
        }
        String username =
                accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
        audit.succeed(audit.begin(actors.resolve(), "RECOVERY_CODE_USE", "user", username, null, null, null, false), 1);
        return Optional.of(Method.RECOVERY_CODE);
    }
}
