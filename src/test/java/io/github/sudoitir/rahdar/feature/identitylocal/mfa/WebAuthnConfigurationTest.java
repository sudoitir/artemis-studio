package io.github.sudoitir.rahdar.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** The relying party and the one allowed origin are what a browser reports for Rahdar's address, not what was typed. */
class WebAuthnConfigurationTest {

    @Test
    void theOriginIsTheHostInLowerCaseWithoutADefaultPort() {
        assertThat(WebAuthnConfiguration.origin(URI.create("https://Rahdar.Example.COM")))
                .isEqualTo("https://rahdar.example.com");
        assertThat(WebAuthnConfiguration.origin(URI.create("HTTPS://rahdar.example.com:443")))
                .isEqualTo("https://rahdar.example.com");
        assertThat(WebAuthnConfiguration.origin(URI.create("http://rahdar.example.com:80")))
                .isEqualTo("http://rahdar.example.com");
    }

    @Test
    void anotherPortAndAPathPrefixAreKeptOrDroppedAsABrowserReportsThem() {
        assertThat(WebAuthnConfiguration.origin(URI.create("https://rahdar.example.com:8443/console")))
                .isEqualTo("https://rahdar.example.com:8443");
        assertThat(WebAuthnConfiguration.origin(URI.create("http://localhost:8080")))
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void theRelyingPartyIdIsTheHostAloneInLowerCase() {
        assertThat(WebAuthnConfiguration.rpId(URI.create("https://Rahdar.Example.com:8443/x")))
                .isEqualTo("rahdar.example.com");
    }
}
