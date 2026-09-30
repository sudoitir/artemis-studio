package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A client that is not a trusted proxy cannot pick its own address (identity-and-sessions spec). */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=203[.]0[.]113[.][0-9]+")
class UntrustedProxyIntegrationTest extends ForwardedAddressTestBase {

    @Test
    void aForgedForwardedForDoesNotEvadeTheThrottle() {
        var statuses = failedLoginsWithForgedAddresses();

        assertThat(statuses.subList(0, 5)).containsOnly(401);
        assertThat(statuses.subList(5, ATTEMPTS)).containsOnly(429);
    }
}
