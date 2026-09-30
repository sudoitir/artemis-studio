package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeAddressTest {

    @Test
    void aNodeIsItsHostAndPortAlone() {
        assertThat(NodeAddress.hostPort("http://admin:s3cret@broker-1:8161/console/jolokia"))
                .isEqualTo("broker-1:8161");
        assertThat(NodeAddress.hostPort("broker-2:61616")).isEqualTo("broker-2:61616");
        assertThat(NodeAddress.hostPort("tcp://broker-3:61616?sslEnabled=true")).isEqualTo("broker-3:61616");
        assertThat(NodeAddress.hostPort("(tcp://a:1,tcp://b:2)")).isEqualTo("a:1");
        assertThat(NodeAddress.hostPort("https://broker-4/jolokia")).isEqualTo("broker-4");
    }

    @Test
    void userinfoIsStrippedBeforeAnythingElseIsCut() {
        assertThat(NodeAddress.hostPort("http://admin:1234,x@broker:8161/console/jolokia"))
                .isEqualTo("broker:8161");
        assertThat(NodeAddress.hostPort("http://admin:p)w?d@broker:8161/console/jolokia"))
                .isEqualTo("broker:8161");
    }

    @Test
    void anAddressThatIsNotOneIsUnknown() {
        assertThat(NodeAddress.hostPort(null)).isEqualTo("unknown");
        assertThat(NodeAddress.hostPort(" ")).isEqualTo("unknown");
    }
}
