package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The control for {@link UntrustedProxyIntegrationTest}: with the default {@code internal-proxies},
 * loopback is trusted, so the same forwarded addresses are honoured. It proves the valve is on, so
 * the untrusted case is not passing only because forwarded headers are ignored altogether.
 */
class TrustedProxyIntegrationTest extends ForwardedAddressTestBase {

    @Test
    void aTrustedProxyDecidesTheClientAddress() {
        assertThat(failedLoginsWithForgedAddresses()).hasSize(ATTEMPTS).containsOnly(401);
    }
}
