package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.MfaStatusView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.RecoveryCodesView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpConfirmedView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpEnrolmentView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.WebAuthnAvailabilityView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts.Method;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * Enrolling the caller's second factors and their recovery codes (identity-and-sessions spec,
 * ADR-0142). The first factor of an account is trust on first use: it needs no step-up, because the
 * user has nothing to step up with, and the enrolment is audited with the source address. Adding or
 * replacing a factor when one exists needs a fresh step-up, which includes that factor.
 */
@Service
@RequiredArgsConstructor
public class MfaEnrolment {

    private static final int SECRET_BYTES = 20; // 160 bits, the size of an HMAC-SHA1 key

    private final TotpStore totp;
    private final RecoveryCodes recoveryCodes;
    private final SecondFactorService factors;
    private final UserAccounts accounts;
    private final SessionAuthentication sessions;
    private final AuditService audit;
    private final ActorResolver actors;
    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public MfaStatusView status(StudioPrincipal principal) {
        UUID userId = principal.userId();
        return new MfaStatusView(
                factors.required(userId),
                factors.enrolled(userId),
                totp.hasActive(userId),
                recoveryCodes.remaining(userId),
                new WebAuthnAvailabilityView(false, "Passkeys are not available yet."));
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
        boolean hadFactor = requireStepUpWhenEnrolled(principal, request);
        UUID userId = principal.userId();
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
        audited(principal, "MFA_ENROL", Map.of("method", Method.TOTP.name(), "firstFactor", !hadFactor));
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

    /** A password-only account of this provider, in a browser session: tokens and other providers do not enrol here. */
    private void requireLocalSession(StudioPrincipal principal) {
        if (principal.tokenName() != null) {
            throw new AccessDeniedException("Second factors are managed from a signed-in session, not with a token.");
        }
        boolean local = accounts.byId(principal.userId())
                .filter(a -> IdentityLocalModule.PROVIDER_ID.equals(a.providerId()))
                .isPresent();
        if (!local) {
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
