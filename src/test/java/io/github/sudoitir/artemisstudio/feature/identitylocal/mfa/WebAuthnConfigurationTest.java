package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** The relying party and the one allowed origin are what a browser reports for Studio's address, not what was typed. */
class WebAuthnConfigurationTest {

    @Test
    void theOriginIsTheHostInLowerCaseWithoutADefaultPort() {
        assertThat(WebAuthnConfiguration.origin(URI.create("https://Studio.Example.COM")))
                .isEqualTo("https://studio.example.com");
        assertThat(WebAuthnConfiguration.origin(URI.create("HTTPS://studio.example.com:443")))
                .isEqualTo("https://studio.example.com");
        assertThat(WebAuthnConfiguration.origin(URI.create("http://studio.example.com:80")))
                .isEqualTo("http://studio.example.com");
    }

    @Test
    void anotherPortAndAPathPrefixAreKeptOrDroppedAsABrowserReportsThem() {
        assertThat(WebAuthnConfiguration.origin(URI.create("https://studio.example.com:8443/console")))
                .isEqualTo("https://studio.example.com:8443");
        assertThat(WebAuthnConfiguration.origin(URI.create("http://localhost:8080")))
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void theRelyingPartyIdIsTheHostAloneInLowerCase() {
        assertThat(WebAuthnConfiguration.rpId(URI.create("https://Studio.Example.com:8443/x")))
                .isEqualTo("studio.example.com");
    }
}
