package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import io.github.sudoitir.artemisstudio.feature.identitylocal.mfa.MfaEnrolment;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.ConfirmTotpRequest;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.MfaStatusView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.PasskeyRegisteredView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.RecoveryCodesView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.RegisterPasskeyRequest;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpConfirmedView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpEnrolmentView;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enrolling second factors for the signed-in local account (identity-and-sessions spec, ADR-0142).
 * A session restricted to enrolment may call exactly the endpoints its filter lets through.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private final MfaEnrolment enrolment;

    @GetMapping
    public MfaStatusView status(@AuthenticationPrincipal StudioPrincipal principal) {
        return enrolment.status(principal);
    }

    /** Start (or restart) setting up an authenticator app. Nothing is active until {@code /totp/confirm}. */
    @PostMapping("/totp")
    public TotpEnrolmentView startTotp(@AuthenticationPrincipal StudioPrincipal principal, HttpServletRequest req) {
        return enrolment.startTotp(principal, req);
    }

    @PostMapping("/totp/confirm")
    public TotpConfirmedView confirmTotp(
            @AuthenticationPrincipal StudioPrincipal principal,
            @Valid @RequestBody ConfirmTotpRequest request,
            HttpServletRequest req,
            HttpServletResponse resp) {
        return enrolment.confirmTotp(principal, request.code(), req, resp);
    }

    /**
     * The options to create a passkey with: the object {@code PublicKeyCredential.parseCreationOptionsFromJSON}
     * takes. Answers {@code 409 passkeys-unavailable} when Studio's public address is not set.
     */
    @PostMapping("/webauthn/options")
    public Map<String, Object> passkeyOptions(
            @AuthenticationPrincipal StudioPrincipal principal, HttpServletRequest req) {
        return enrolment.passkeyOptions(principal, req);
    }

    /** Keep the passkey the browser created; the first factor an account holds also gets recovery codes. */
    @PostMapping("/webauthn")
    public PasskeyRegisteredView registerPasskey(
            @AuthenticationPrincipal StudioPrincipal principal,
            @Valid @RequestBody RegisterPasskeyRequest request,
            HttpServletRequest req,
            HttpServletResponse resp) {
        return enrolment.registerPasskey(principal, request.label(), request.credential(), req, resp);
    }

    /** New recovery codes; the old ones stop working. */
    @PostMapping("/recovery-codes")
    public RecoveryCodesView regenerateRecoveryCodes(
            @AuthenticationPrincipal StudioPrincipal principal, HttpServletRequest req) {
        return enrolment.regenerateRecoveryCodes(principal, req);
    }
}
