package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.platform.clusters.SeedExpander.Seed;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The resolver is stubbed, so the tests need no DNS. */
class SeedExpanderTest {

    private static final String PATH = ":8161/console/jolokia";

    private static InetAddress ip(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SeedExpander resolvingTo(String... addresses) {
        return new SeedExpander(host ->
                java.util.Arrays.stream(addresses).map(SeedExpanderTest::ip).toArray(InetAddress[]::new));
    }

    @Test
    void aHostWithSeveralAddressesBecomesOneSeedPerAddress() {
        List<Seed> seeds = resolvingTo("10.0.0.1", "10.0.0.2", "10.0.0.3").expand("http://brokers.example" + PATH);

        assertThat(seeds)
                .containsExactly(
                        new Seed("http://10.0.0.1" + PATH, true),
                        new Seed("http://10.0.0.2" + PATH, true),
                        new Seed("http://10.0.0.3" + PATH, true));
    }

    @Test
    void aTlsSeedKeepsItsHostNameAndOnlyLearnsFromTheAddresses() {
        List<Seed> seeds = resolvingTo("10.0.0.1", "10.0.0.2").expand("https://brokers.example" + PATH);

        assertThat(seeds)
                .containsExactly(
                        new Seed("https://brokers.example" + PATH, true),
                        new Seed("https://10.0.0.1" + PATH, false),
                        new Seed("https://10.0.0.2" + PATH, false));
    }

    @Test
    void aHostWithOneAddressStaysAsGiven() {
        assertThat(resolvingTo("10.0.0.1").expand("http://broker-1" + PATH))
                .containsExactly(new Seed("http://broker-1" + PATH, true));
    }

    @Test
    void aNameWithMoreAddressesThanAClusterHasBrokersIsCutAtSixteen() {
        String[] many = java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(i -> "10.0.0." + i)
                .toArray(String[]::new);

        List<Seed> seeds = resolvingTo(many).expand("http://brokers.example" + PATH);

        assertThat(seeds).hasSize(SeedExpander.MAX_ADDRESSES);
        assertThat(seeds.getFirst().url()).isEqualTo("http://10.0.0.1" + PATH);
        assertThat(seeds.getLast().url()).isEqualTo("http://10.0.0.16" + PATH);
    }

    @Test
    void aDualStackHostIsOneHostAndStaysAsGiven() {
        assertThat(resolvingTo("127.0.0.1", "::1").expand("http://localhost" + PATH))
                .containsExactly(new Seed("http://localhost" + PATH, true));
    }

    @Test
    void aHostThatDoesNotResolveStaysAsGivenForTheProbeToReport() {
        SeedExpander expander = new SeedExpander(host -> {
            throw new UnknownHostException(host);
        });

        assertThat(expander.expand("http://nowhere.invalid" + PATH))
                .containsExactly(new Seed("http://nowhere.invalid" + PATH, true));
    }

    @Test
    void anIpv6AddressIsBracketedInItsUrl() {
        List<Seed> seeds = resolvingTo("2001:db8::1", "2001:db8::2").expand("http://brokers.example" + PATH);

        assertThat(seeds)
                .extracting(Seed::url)
                .allSatisfy(url -> assertThat(url).startsWith("http://[2001:"));
    }
}
