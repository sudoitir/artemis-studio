package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts.Method;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.time.Duration;
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
 * The second factors of local accounts as the sign-in path sees them (ADR-0143): TOTP, passkeys and
 * recovery codes. Only accounts of the local provider are ever required to hold one; any other provider
 * does its own multi-factor authentication.
 */
@Component
@RequiredArgsConstructor
class SecondFactorService implements SecondFactors {

    private final TotpStore totp;
    private final Passkeys passkeys;
    private final RecoveryCodes recoveryCodes;
    private final TrustedDevices trustedDevices;
    private final UserAccounts accounts;
    private final AuditService audit;
    private final ActorResolver actors;

    @Override
    public boolean enrolled(UUID userId) {
        // A passkey counts even while passkeys are unavailable: an account that had one is still an
        // account with a second factor, and a recovery code completes its sign-in.
        return totp.hasActive(userId) || passkeys.count(userId) > 0;
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
        if (passkeys.available() && passkeys.count(userId) > 0) {
            methods.add(Method.WEBAUTHN);
        }
        if (totp.hasActive(userId)) {
            methods.add(Method.TOTP);
        }
        if (enrolled(userId) && recoveryCodes.remaining(userId) > 0) {
            methods.add(Method.RECOVERY_CODE);
        }
        return methods;
    }

    @Override
    public List<Method> enrolledMethods(UUID userId) {
        List<Method> methods = new ArrayList<>();
        if (totp.hasActive(userId)) {
            methods.add(Method.TOTP);
        }
        if (passkeys.count(userId) > 0) {
            methods.add(Method.WEBAUTHN);
        }
        return methods;
    }

    @Override
    public void reset(UUID userId) {
        totp.remove(userId);
        passkeys.removeAll(userId);
        recoveryCodes.removeAll(userId);
        trustedDevices.revokeAll(userId, "second factors reset");
    }

    @Override
    public Duration trustedDeviceLifetime() {
        return trustedDevices.lifetime();
    }

    @Override
    public boolean useTrustedDevice(UUID userId, String token) {
        return trustedDevices.use(userId, token);
    }

    @Override
    public String trustDevice(UUID userId, String clientAddress, String userAgent) {
        return trustedDevices.create(userId, clientAddress, userAgent);
    }

    @Override
    public void revokeTrustedDevices(UUID userId, String reason) {
        trustedDevices.revokeAll(userId, reason);
    }

    @Override
    public Optional<PasskeyChallenge> passkeyChallenge(UUID userId) {
        return passkeys.challenge(userId);
    }

    @Override
    @Transactional
    public Optional<Method> verify(UUID userId, Proof proof) {
        return switch (proof) {
            case TotpCode t -> verifyTotp(userId, t.code());
            case RecoveryCode r -> verifyRecoveryCode(userId, r.code());
            case WebAuthnAssertion w -> passkeys.verify(userId, w) ? Optional.of(Method.WEBAUTHN) : Optional.empty();
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
