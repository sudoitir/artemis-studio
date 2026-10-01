package io.github.sudoitir.artemisstudio.kernel.security;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The second factors of password accounts (ADR-0143), as the sign-in path sees them. Implemented by
 * the identity module that holds the factors; when it is switched off there is no bean and sign-in
 * is password only. The kernel asks the questions and keeps the session state; it never sees a
 * secret.
 */
public interface SecondFactors {

    /** Whether the user holds a factor a sign-in can be completed with. */
    boolean enrolled(UUID userId);

    /**
     * Whether the user must hold one: their provider checks a password (the local provider, or a
     * plugin's sign-in, ADR-0153) and they hold a role that requires it. A user of a redirect provider
     * is never required, because that provider does its own MFA.
     */
    boolean required(UUID userId);

    /** The ways the user can prove a second factor now, best first. Empty when they are not enrolled. */
    List<SessionFacts.Method> methods(UUID userId);

    /** The factors the user has set up, for an administrator to see: TOTP and WEBAUTHN, never the recovery codes. */
    List<SessionFacts.Method> enrolledMethods(UUID userId);

    /**
     * Remove everything the user signs in with beyond the password: authenticator app, passkeys, recovery
     * codes and trusted devices. It audits nothing about the factors themselves: the caller records why.
     */
    void reset(UUID userId);

    /** How long a browser stays trusted after a second factor; zero when trusted devices are switched off (ADR-0143). */
    Duration trustedDeviceLifetime();

    /**
     * Whether {@code token}, read from a trusted-device cookie, is a live trusted device of this user
     * (in constant time, and within the lifetime now in force). A device that is live has its use recorded.
     */
    boolean useTrustedDevice(UUID userId, String token);

    /** Trust the browser that just gave a second factor; returns the token to put in its cookie, which is never stored. */
    String trustDevice(UUID userId, String clientAddress, String userAgent);

    /** Stop trusting every browser of the user, for a reason that goes in the audit trail. */
    void revokeTrustedDevices(UUID userId, String reason);

    /**
     * A challenge for the passkeys of the user, to be answered with a {@link WebAuthnAssertion}; empty
     * when the user has none they can use now (none enrolled, or passkeys are not available).
     */
    Optional<PasskeyChallenge> passkeyChallenge(UUID userId);

    /**
     * Checks a proof and spends it: a proof, however it is presented, verifies once.
     *
     * @return how it was verified, or empty when it was not valid or was already used
     */
    Optional<SessionFacts.Method> verify(UUID userId, Proof proof);

    /** A required user who has nothing to sign in with yet may only enrol. */
    default boolean enrolmentRequired(UUID userId) {
        return required(userId) && !enrolled(userId);
    }

    /**
     * What to show the browser so it can ask for a passkey, and what the session keeps meanwhile.
     *
     * @param options the WebAuthn request options as JSON, exactly as {@code navigator.credentials.get} takes them
     * @param state whatever the implementation needs to check the answer against; the session holds it
     *     and hands it back in the {@link WebAuthnAssertion}, and never looks inside
     */
    record PasskeyChallenge(String options, Serializable state) {}

    /** What a user presents as a second factor. Later factors add a case here. */
    sealed interface Proof permits TotpCode, RecoveryCode, WebAuthnAssertion {}

    /** A code from an authenticator app. */
    record TotpCode(String code) implements Proof {
        @Override
        public String toString() {
            return "TotpCode[***]";
        }
    }

    /**
     * A browser's answer to a {@link PasskeyChallenge}.
     *
     * @param challenge the {@link PasskeyChallenge#state()} the session held
     * @param credential the {@code PublicKeyCredential} the browser returned, as JSON
     */
    record WebAuthnAssertion(Serializable challenge, String credential) implements Proof {
        @Override
        public String toString() {
            return "WebAuthnAssertion[***]";
        }
    }

    /** One of the single-use codes issued at the first enrolment. */
    record RecoveryCode(String code) implements Proof {
        @Override
        public String toString() {
            return "RecoveryCode[***]";
        }
    }
}
