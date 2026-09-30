package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.MfaStatusView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.PasskeyRegisteredView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.PasskeyView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.RecoveryCodesView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpConfirmedView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpEnrolmentView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TrustedDeviceView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.WebAuthnAvailabilityView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts.Method;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TrustedDeviceCookie;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * Enrolling the caller's second factors (an authenticator app, passkeys) and their recovery codes (identity-and-sessions spec,
 * ADR-0143). The first factor of an account is trust on first use: it needs no step-up, because the
 * user has nothing to step up with, and the enrolment is audited with the source address. Adding or
 * replacing a factor when one exists needs a fresh step-up, which includes that factor.
 */
@Service
@RequiredArgsConstructor
public class MfaEnrolment {

    private static final String METHOD = "method";
    private static final int SECRET_BYTES = 20; // 160 bits, the size of an HMAC-SHA1 key

    private final TotpStore totp;
    private final Passkeys passkeys;
    private final RecoveryCodes recoveryCodes;
    private final TrustedDevices trustedDevices;
    private final SecondFactorService factors;
    private final UserAccounts accounts;
    private final SessionAuthentication sessions;
    private final AuditService audit;
    private final ActorResolver actors;
    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public MfaStatusView status(StudioPrincipal principal, HttpServletRequest request) {
        requireSession(principal);
        UUID userId = principal.userId();
        Duration lifetime = trustedDevices.lifetime();
        String cookie = TrustedDeviceCookie.read(request).orElse(null);
        return new MfaStatusView(
                isLocal(principal),
                factors.required(userId),
                factors.enrolled(userId),
                totp.hasActive(userId),
                recoveryCodes.remaining(userId),
                new WebAuthnAvailabilityView(
                        passkeys.available(), passkeys.available() ? null : Passkeys.UNAVAILABLE_REASON),
                passkeys.of(userId).stream().map(MfaEnrolment::view).toList(),
                trustedDevices.live(userId).stream()
                        .map(d -> new TrustedDeviceView(
                                d.id(),
                                d.userAgent(),
                                d.clientAddress(),
                                d.createdAt(),
                                d.lastUsedAt(),
                                TrustedDevices.effectiveExpiry(d, lifetime),
                                TrustedDevices.isCurrent(d, cookie)))
                        .toList());
    }

    /** Stop trusting one of the caller's browsers; the browser it is, if it is this one, is told to forget it. */
    @Transactional
    public void revokeTrustedDevice(
            StudioPrincipal principal, UUID deviceId, HttpServletRequest request, HttpServletResponse response) {
        requireLocalSession(principal);
        String cookie = TrustedDeviceCookie.read(request).orElse(null);
        boolean thisBrowser = trustedDevices.live(principal.userId()).stream()
                .anyMatch(d -> d.id().equals(deviceId) && TrustedDevices.isCurrent(d, cookie));
        if (!trustedDevices.revoke(principal.userId(), deviceId)) {
            throw new NotFoundException("trusted device", deviceId);
        }
        if (thisBrowser) {
            TrustedDeviceCookie.clear(request, response);
        }
    }

    /** Stop trusting every one of the caller's browsers, this one included. */
    @Transactional
    public void revokeTrustedDevices(
            StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        requireLocalSession(principal);
        trustedDevices.revokeAll(principal.userId(), "revoked by the user");
        TrustedDeviceCookie.clear(request, response);
    }

    private static PasskeyView view(CredentialRecord passkey) {
        return new PasskeyView(
                passkey.getCredentialId().toBase64UrlString(),
                passkey.getLabel(),
                passkey.getCreated(),
                passkey.getLastUsed());
    }

    /** The options the browser needs to create a passkey; nothing is kept until {@link #registerPasskey}. */
    @Transactional
    public Map<String, Object> passkeyOptions(StudioPrincipal principal, HttpServletRequest request) {
        requireLocalSession(principal);
        requirePasskeysAvailable();
        requireStepUpWhenEnrolled(principal, request);
        return passkeys.creationOptions(principal, request);
    }

    /**
     * Keep the passkey the browser created from the options this session was given. Like a confirmed
     * authenticator app it lifts the enrolment restriction, and it is the first factor's moment for recovery codes.
     */
    @Transactional
    public PasskeyRegisteredView registerPasskey(
            StudioPrincipal principal,
            String label,
            Map<String, Object> credential,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireLocalSession(principal);
        requirePasskeysAvailable();
        accounts.lock(principal.userId());
        boolean hadFactor = requireStepUpWhenEnrolled(principal, request);
        CredentialRecord passkey = passkeys.register(principal.userId(), label.strip(), credential, request);
        List<String> codes = hadFactor ? null : recoveryCodes.issue(principal.userId());
        audited(
                principal,
                "MFA_ENROL",
                Map.of(METHOD, Method.WEBAUTHN.name(), "firstFactor", !hadFactor, "label", passkey.getLabel()));
        sessions.reestablishAfterEnrolment(principal, Method.WEBAUTHN, request, response);
        return new PasskeyRegisteredView(view(passkey), codes);
    }

    private void requirePasskeysAvailable() {
        if (!passkeys.available()) {
            throw new ConflictException("passkeys-unavailable", Passkeys.UNAVAILABLE_REASON);
        }
    }

    @Transactional
    public TotpEnrolmentView startTotp(StudioPrincipal principal, HttpServletRequest request) {
        requireLocalSession(principal);
        requireStepUpWhenEnrolled(principal, request);
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String base32 = Base32.encode(secret);
        totp.savePending(principal.userId(), base32);
        return new TotpEnrolmentView(base32, otpauthUri(principal.getUsername(), base32));
    }

    @Transactional
    public TotpConfirmedView confirmTotp(
            StudioPrincipal principal, String code, HttpServletRequest request, HttpServletResponse response) {
        requireLocalSession(principal);
        UUID userId = principal.userId();
        accounts.lock(userId);
        boolean hadFactor = requireStepUpWhenEnrolled(principal, request);
        byte[] pending = totp.pending(userId)
                .orElseThrow(() -> new ConflictException(
                        "no-pending-totp", "There is no authenticator waiting for a code. Start the set-up again."));
        OptionalLong step = Totp.matchingStep(pending, code, Instant.now());
        if (step.isEmpty()) {
            throw new IllegalArgumentException(
                    "That code does not match. Check the clock on your phone, then try the next code.");
        }
        if (!totp.activate(userId, step.getAsLong())) {
            throw new ConflictException("no-pending-totp", "That authenticator was already confirmed.");
        }
        List<String> codes = hadFactor ? null : recoveryCodes.issue(userId);
        audited(principal, "MFA_ENROL", Map.of(METHOD, Method.TOTP.name(), "firstFactor", !hadFactor));
        sessions.reestablishAfterEnrolment(principal, Method.TOTP, request, response);
        return new TotpConfirmedView(codes);
    }

    @Transactional
    public RecoveryCodesView regenerateRecoveryCodes(StudioPrincipal principal, HttpServletRequest request) {
        requireLocalSession(principal);
        if (!requireStepUpWhenEnrolled(principal, request)) {
            throw new ConflictException(
                    "mfa-not-enrolled", "Recovery codes belong to a second factor. Set one up first.");
        }
        List<String> codes = recoveryCodes.issue(principal.userId());
        audited(principal, "RECOVERY_CODES_REGENERATE", null);
        return new RecoveryCodesView(codes);
    }

    /** Remove the caller's authenticator app. */
    @Transactional
    public void removeTotp(StudioPrincipal principal, HttpServletRequest request) {
        UUID userId = principal.userId();
        int remaining = passkeys.count(userId);
        guardRemoval(
                principal,
                request,
                totp.hasActive(userId),
                "no-totp",
                "You have no authenticator app to remove.",
                remaining);
        totp.remove(userId);
        removed(principal, Method.TOTP, remaining == 0);
    }

    /** Remove one of the caller's passkeys, by the id {@code GET /auth/mfa} lists it under. */
    @Transactional
    public void removePasskey(StudioPrincipal principal, String credentialId, HttpServletRequest request) {
        requireLocalSession(principal);
        UUID userId = principal.userId();
        boolean theirs = passkeys.of(userId).stream()
                .anyMatch(p -> p.getCredentialId().toBase64UrlString().equals(credentialId));
        if (!theirs) {
            throw new NotFoundException("passkey", credentialId);
        }
        int remaining = (totp.hasActive(userId) ? 1 : 0) + passkeys.count(userId) - 1;
        guardRemoval(principal, request, true, null, null, remaining);
        passkeys.remove(userId, credentialId);
        removed(principal, Method.WEBAUTHN, remaining == 0);
    }

    /**
     * Removing a factor needs a step-up, and a user who must hold one keeps at least one.
     *
     * @param present whether the factor to remove is there; when it is not, the conflict {@code missingSlug}
     * @param remaining how many factors the user has left once it is removed
     */
    private void guardRemoval(
            StudioPrincipal principal,
            HttpServletRequest request,
            boolean present,
            String missingSlug,
            String missingMessage,
            int remaining) {
        requireLocalSession(principal);
        if (!sessions.recentlyAuthenticated(request)) {
            throw new ReauthenticationRequiredException();
        }
        if (!present) {
            throw new ConflictException(missingSlug, missingMessage);
        }
        if (remaining == 0 && factors.required(principal.userId())) {
            throw new ConflictException("last-factor-required", "Add another way to sign in first.");
        }
    }

    /** The factor is gone: audit it, and when it was the last one, the recovery codes and trusted devices go with it. */
    private void removed(StudioPrincipal principal, Method method, boolean wasLast) {
        if (wasLast) {
            recoveryCodes.removeAll(principal.userId());
            trustedDevices.revokeAll(principal.userId(), "last factor removed");
        }
        audited(principal, "MFA_REMOVE", Map.of(METHOD, method.name(), "lastFactor", wasLast));
    }

    /** A password-only account of this provider, in a browser session: tokens and other providers do not enrol here. */
    private boolean isLocal(StudioPrincipal principal) {
        return accounts.byId(principal.userId())
                .filter(a -> IdentityLocalModule.PROVIDER_ID.equals(a.providerId()))
                .isPresent();
    }

    /** A key acts within its narrowed grants; it neither manages nor reads its owner's second factors. */
    private static void requireSession(StudioPrincipal principal) {
        if (principal.tokenName() != null) {
            throw new AccessDeniedException("Second factors are managed from a signed-in session, not with a token.");
        }
    }

    private void requireLocalSession(StudioPrincipal principal) {
        requireSession(principal);
        if (!isLocal(principal)) {
            throw new ConflictException(
                    "mfa-not-local",
                    "Your account signs in through another provider, which manages two-step verification.");
        }
    }

    /** @return whether the user already holds a factor */
    private boolean requireStepUpWhenEnrolled(StudioPrincipal principal, HttpServletRequest request) {
        boolean enrolled = factors.enrolled(principal.userId());
        if (enrolled && !sessions.recentlyAuthenticated(request)) {
            throw new ReauthenticationRequiredException();
        }
        return enrolled;
    }

    private void audited(StudioPrincipal principal, String action, Map<String, ?> params) {
        audit.succeed(
                audit.begin(actors.resolve(), action, "user", principal.getUsername(), null, null, params, false), 1);
    }

    /** The provisioning URI authenticator apps read from a QR code. */
    private static String otpauthUri(String username, String base32Secret) {
        String issuer = UriUtils.encode(Branding.PRODUCT_NAME, StandardCharsets.UTF_8);
        return "otpauth://totp/" + issuer + ":" + UriUtils.encode(username, StandardCharsets.UTF_8) + "?secret="
                + base32Secret + "&issuer=" + issuer + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period="
                + Totp.STEP_SECONDS;
    }
}
