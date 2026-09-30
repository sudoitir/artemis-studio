package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A software authenticator with a browser in front of it, from webauthn4j's own test support: it
 * answers the options Studio returns the way a real passkey does, so the tests register and sign in
 * with a genuine attestation and assertion instead of a mock of the verifier. It keeps the passkeys
 * it creates, as a device would.
 */
final class PasskeyDevice {

    private final ObjectConverter converter = new ObjectConverter();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ClientPlatform browser;

    /** A device whose browser is at {@code origin}, e.g. {@code https://studio.example.test}. */
    PasskeyDevice(String origin) {
        this.browser = new ClientPlatform(
                new Origin(origin), new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
    }

    /** {@code navigator.credentials.create} for these creation options (as Studio returned them); the credential as JSON. */
    String create(String optionsJson) {
        PublicKeyCredentialCreationOptions options =
                converter.getJsonConverter().readValue(optionsJson, PublicKeyCredentialCreationOptions.class);
        return converter.getJsonConverter().writeValueAsString(browser.create(options));
    }

    /**
     * {@code navigator.credentials.get} for these request options, with the list of allowed credentials
     * dropped: what a hostile page would ask this device for, so any passkey it holds can answer.
     */
    String getIgnoringAllowList(String optionsJson) {
        ObjectNode options = (ObjectNode) json.readTree(optionsJson);
        options.remove("allowCredentials");
        return get(options.toString());
    }

    /** {@code navigator.credentials.get} for these request options; the credential as JSON. */
    String get(String optionsJson) {
        PublicKeyCredentialRequestOptions options =
                converter.getJsonConverter().readValue(optionsJson, PublicKeyCredentialRequestOptions.class);
        return converter.getJsonConverter().writeValueAsString(browser.get(options));
    }
}
