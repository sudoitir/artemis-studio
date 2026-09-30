package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import io.github.sudoitir.artemisstudio.feature.identitylocal.mfa.MfaEnrolment;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.ConfirmTotpRequest;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.MfaStatusView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.RecoveryCodesView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpConfirmedView;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.MfaViews.TotpEnrolmentView;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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

    /** New recovery codes; the old ones stop working. */
    @PostMapping("/recovery-codes")
    public RecoveryCodesView regenerateRecoveryCodes(
            @AuthenticationPrincipal StudioPrincipal principal, HttpServletRequest req) {
        return enrolment.regenerateRecoveryCodes(principal, req);
    }
}
