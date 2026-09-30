package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors.PasskeyChallenge;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors.WebAuthnAssertion;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A user's passkeys (ADR-0142): the user entity, whose {@code name} is the user's id so a renamed
 * account keeps its passkeys, and the WebAuthn ceremonies over Spring Security's operations. A
 * challenge lives in the session and answers one attempt. This class writes no audit and owns no
 * transaction; the callers that change what a user can sign in with do both.
 */
@Slf4j
@Component
class Passkeys {

    /** Why passkeys cannot be enrolled or used, and what to do about it. */
    static final String UNAVAILABLE_REASON =
            "Set ARTEMIS_STUDIO_PUBLIC_URL to the address people open Studio at to enable passkeys.";

    private static final String ENROLMENT = SessionAuthentication.PENDING_PREFIX + "PASSKEY_ENROLMENT";

    /** The creation options a session was given, until it registers a passkey with them. */
    record PendingEnrolment(PublicKeyCredentialCreationOptions options, Instant at) implements Serializable {}

    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository credentials;
    private final Optional<WebAuthnRelyingPartyOperations> relyingParty;
    private final JsonMapper json =
            JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    Passkeys(
            PublicKeyCredentialUserEntityRepository userEntities,
            UserCredentialRepository credentials,
            Optional<WebAuthnRelyingPartyOperations> relyingParty) {
        this.userEntities = userEntities;
        this.credentials = credentials;
        this.relyingParty = relyingParty;
    }

    boolean available() {
        return relyingParty.isPresent();
    }

    private WebAuthnRelyingPartyOperations relyingParty() {
        return relyingParty.orElseThrow(() -> new IllegalStateException(UNAVAILABLE_REASON));
    }

    /** The user's passkeys, oldest first. */
    List<CredentialRecord> of(UUID userId) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(userId.toString());
        return entity == null
                ? List.of()
                : credentials.findByUserId(entity.getId()).stream()
                        .sorted(java.util.Comparator.comparing(CredentialRecord::getCreated))
                        .toList();
    }

    int count(UUID userId) {
        return of(userId).size();
    }

    /** Remove one of the user's passkeys by its id (base64url); false when they have no such passkey. */
    boolean remove(UUID userId, String credentialId) {
        boolean theirs = of(userId).stream()
                .anyMatch(c -> c.getCredentialId().toBase64UrlString().equals(credentialId));
        if (theirs) {
            credentials.delete(Bytes.fromBase64(credentialId));
        }
        return theirs;
    }

    void removeAll(UUID userId) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(userId.toString());
        if (entity != null) {
            credentials.findByUserId(entity.getId()).forEach(c -> credentials.delete(c.getCredentialId()));
            userEntities.delete(entity.getId());
        }
    }

    // ---- enrolment ---------------------------------------------------------------------------

    /** Options for {@code navigator.credentials.create}; the session keeps them for {@link #register}. */
    Map<String, Object> creationOptions(StudioPrincipal principal, HttpServletRequest request) {
        UUID userId = principal.userId();
        if (userEntities.findByUsername(userId.toString()) == null) {
            userEntities.save(ImmutablePublicKeyCredentialUserEntity.builder()
                    .id(Bytes.random())
                    .name(userId.toString())
                    .displayName(principal.getUsername())
                    .build());
        }
        PublicKeyCredentialCreationOptions options = relyingParty()
                .createPublicKeyCredentialCreationOptions(
                        new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication(userId)));
        request.getSession().setAttribute(ENROLMENT, new PendingEnrolment(options, Instant.now()));
        return json.readValue(json.writeValueAsString(options), new TypeReference<>() {});
    }

    /**
     * Verify the browser's answer to the options this session was given, and keep the passkey. The
     * options are spent by asking, so an answer counts once.
     *
     * @throws IllegalArgumentException with a reason for the user when the answer is not accepted
     */
    CredentialRecord register(UUID userId, String label, Map<String, Object> credential, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        PendingEnrolment pending = session == null ? null : (PendingEnrolment) session.getAttribute(ENROLMENT);
        if (session != null) {
            session.removeAttribute(ENROLMENT);
        }
        if (pending == null
                || pending.at().isBefore(Instant.now().minus(SessionAuthentication.PENDING_WINDOW))
                || !pending.options().getUser().getName().equals(userId.toString())) {
            throw new IllegalArgumentException(
                    "The passkey prompt has expired. Choose “Add a passkey” to start again.");
        }
        try {
            RelyingPartyPublicKey publicKey =
                    json.convertValue(Map.of("label", label, "credential", credential), RelyingPartyPublicKey.class);
            return relyingParty()
                    .registerCredential(new ImmutableRelyingPartyRegistrationRequest(pending.options(), publicKey));
        } catch (RuntimeException e) {
            log.debug("A passkey was not accepted", e);
            throw new IllegalArgumentException(
                    e.getMessage() != null && e.getMessage().contains("already exists")
                            ? "That passkey is already registered."
                            : "That passkey could not be verified. Try again, or add an authenticator app instead.",
                    e);
        }
    }

    // ---- sign-in -----------------------------------------------------------------------------

    /** A challenge for the user's passkeys; empty when they have none, or passkeys are unavailable. */
    Optional<PasskeyChallenge> challenge(UUID userId) {
        if (!available() || count(userId) == 0) {
            return Optional.empty();
        }
        PublicKeyCredentialRequestOptions options = relyingParty()
                .createCredentialRequestOptions(
                        new ImmutablePublicKeyCredentialRequestOptionsRequest(authentication(userId)));
        return Optional.of(new PasskeyChallenge(json.writeValueAsString(options), options));
    }

    /**
     * Whether the answer is a valid assertion, from one of this user's own passkeys, to the
     * challenge they were issued.
     */
    boolean verify(UUID userId, WebAuthnAssertion answer) {
        if (!available() || !(answer.challenge() instanceof PublicKeyCredentialRequestOptions options)) {
            return false;
        }
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> credential =
                    json.readValue(answer.credential(), new TypeReference<>() {});
            PublicKeyCredentialUserEntity owner =
                    relyingParty().authenticate(new RelyingPartyAuthenticationRequest(options, credential));
            return owner.getName().equals(userId.toString());
        } catch (RuntimeException e) {
            log.debug("A passkey assertion was not accepted", e);
            return false;
        }
    }

    /** Spring's operations look the user up by the name of an authenticated principal, which here is the user's id. */
    private static Authentication authentication(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }
}
