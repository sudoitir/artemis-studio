package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** The second-factor enrolment requests and views (identity-and-sessions spec, ADR-0142). */
public final class MfaViews {

    private MfaViews() {}

    /**
     * Where the caller's second factors stand.
     *
     * @param required the caller's role requires a second factor
     * @param enrolled the caller holds one that can complete a sign-in
     */
    public record MfaStatusView(
            @Schema(requiredMode = REQUIRED) boolean required,
            @Schema(requiredMode = REQUIRED) boolean enrolled,
            @Schema(requiredMode = REQUIRED) boolean totpEnrolled,
            @Schema(requiredMode = REQUIRED) int recoveryCodesRemaining,
            @Schema(requiredMode = REQUIRED) WebAuthnAvailabilityView webauthn) {}

    /** Whether passkeys can be enrolled; {@code reason} says why not, and what to configure. */
    public record WebAuthnAvailabilityView(
            @Schema(requiredMode = REQUIRED) boolean available,
            @Schema(nullable = true) String reason) {}

    /**
     * A new authenticator waiting for its first code. {@code secret} is Base32 for typing in;
     * {@code otpauthUri} is what the QR code encodes.
     */
    public record TotpEnrolmentView(
            @Schema(requiredMode = REQUIRED) String secret,
            @Schema(requiredMode = REQUIRED) String otpauthUri) {}

    public record ConfirmTotpRequest(@NotBlank String code) {}

    /** {@code recoveryCodes} is set only when this was the caller's first factor; it is the only time they are shown. */
    public record TotpConfirmedView(@Schema(nullable = true) List<String> recoveryCodes) {}

    public record RecoveryCodesView(
            @Schema(requiredMode = REQUIRED) List<String> codes) {}
}
