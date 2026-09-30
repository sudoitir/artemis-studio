package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The second factors of local accounts (ADR-0142), as the sign-in path sees them. Implemented by
 * the identity module that holds the factors; when it is switched off there is no bean and sign-in
 * is password only. The kernel asks the questions and keeps the session state; it never sees a
 * secret.
 */
public interface SecondFactors {

    /** Whether the user holds a factor a sign-in can be completed with. */
    boolean enrolled(UUID userId);

    /**
     * Whether the user must hold one: they sign in with the local provider and hold a role that
     * requires it. A user of any other provider is never required, because that provider does its own MFA.
     */
    boolean required(UUID userId);

    /** The ways the user can prove a second factor now, best first. Empty when they are not enrolled. */
    List<SessionFacts.Method> methods(UUID userId);

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

    /** What a user presents as a second factor. Later factors add a case here. */
    sealed interface Proof permits TotpCode, RecoveryCode {}

    /** A code from an authenticator app. */
    record TotpCode(String code) implements Proof {
        @Override
        public String toString() {
            return "TotpCode[***]";
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
