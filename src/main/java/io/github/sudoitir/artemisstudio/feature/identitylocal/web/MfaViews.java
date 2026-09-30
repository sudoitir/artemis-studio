package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The second-factor enrolment requests and views (identity-and-sessions spec, ADR-0143). */
public final class MfaViews {

    private MfaViews() {}

    /**
     * Where the caller's second factors stand.
     *
     * @param local the caller's account is a local one; any other provider's identity provider manages two-step
     *     verification, and there is nothing here to set up
     * @param required the caller's role requires a second factor
     * @param enrolled the caller holds one that can complete a sign-in
     * @param passkeys the caller's passkeys, oldest first, whether or not passkeys can be used now
     */
    public record MfaStatusView(
            @Schema(requiredMode = REQUIRED) boolean local,
            @Schema(requiredMode = REQUIRED) boolean required,
            @Schema(requiredMode = REQUIRED) boolean enrolled,
            @Schema(requiredMode = REQUIRED) boolean totpEnrolled,
            @Schema(requiredMode = REQUIRED) int recoveryCodesRemaining,
            @Schema(requiredMode = REQUIRED) WebAuthnAvailabilityView webauthn,
            @Schema(requiredMode = REQUIRED) List<PasskeyView> passkeys,
            @Schema(requiredMode = REQUIRED) List<TrustedDeviceView> trustedDevices) {}

    /** Whether passkeys can be enrolled; {@code reason} says why not, and what to configure. */
    public record WebAuthnAvailabilityView(
            @Schema(requiredMode = REQUIRED) boolean available,
            @Schema(nullable = true) String reason) {}

    /** One of the caller's passkeys. {@code id} is what {@code DELETE /auth/mfa/webauthn/{id}} takes. */
    public record PasskeyView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) Instant created,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "When the passkey was last used to sign in; when it was created until then.")
            Instant lastUsed) {}

    /**
     * A browser the caller chose to trust after a second factor. {@code client} is its User-Agent and
     * {@code address} where it last signed in from; {@code expires} is when it stops counting, taking the
     * trusted-device lifetime now in force into account; {@code current} marks the browser making this request.
     */
    public record TrustedDeviceView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(nullable = true) String client,
            @Schema(nullable = true) String address,
            @Schema(requiredMode = REQUIRED) Instant created,
            @Schema(requiredMode = REQUIRED) Instant lastUsed,
            @Schema(requiredMode = REQUIRED) Instant expires,
            @Schema(requiredMode = REQUIRED) boolean current) {}

    /**
     * The passkey to register: the {@code label} the user gave it, and the credential the browser
     * returned for the options from {@code POST /auth/mfa/webauthn/options}, as
     * {@code PublicKeyCredential.toJSON()} gives it.
     */
    public record RegisterPasskeyRequest(
            @NotBlank @Size(max = 100) String label,

            @NotNull @Schema(requiredMode = REQUIRED) Map<String, Object> credential) {}

    /** {@code recoveryCodes} is set only when this was the caller's first factor; it is the only time they are shown. */
    public record PasskeyRegisteredView(
            @Schema(requiredMode = REQUIRED) PasskeyView passkey,
            @Schema(nullable = true) List<String> recoveryCodes) {}

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
