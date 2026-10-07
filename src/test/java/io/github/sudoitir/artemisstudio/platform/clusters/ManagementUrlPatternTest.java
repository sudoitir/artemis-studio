package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagementUrlPatternTest {

    @Test
    void theDefaultKeepsTheSeedsSchemePortAndPathAndFreesTheHost() {
        assertThat(ManagementUrlPattern.defaultFor("https://broker-1:8443/console/jolokia"))
                .isEqualTo("https://{host}:8443/console/jolokia");
        assertThat(ManagementUrlPattern.defaultFor("http://broker-1/jolokia")).isEqualTo("http://{host}/jolokia");
    }

    @Test
    void aSeedThatIsNotAUrlImpliesNoPattern() {
        assertThat(ManagementUrlPattern.defaultFor("not a url")).isNull();
        assertThat(ManagementUrlPattern.defaultFor("broker-1:8161")).isNull();
    }

    @Test
    void aNodesUrlIsThePatternWithTheConnectorHost() {
        assertThat(ManagementUrlPattern.derive("http://{host}:8161/console/jolokia", "artemis-backup"))
                .isEqualTo("http://artemis-backup:8161/console/jolokia");
        assertThat(ManagementUrlPattern.derive("http://{host}:8161/jolokia", "2001:db8::1"))
                .isEqualTo("http://[2001:db8::1]:8161/jolokia");
        // A connector that names an IPv6 address already brackets it.
        assertThat(ManagementUrlPattern.derive("http://{host}:8161/jolokia", "[::1]"))
                .isEqualTo("http://[::1]:8161/jolokia");
    }

    @Test
    void aPatternNeedsTheHostPlaceholderAndAnHttpScheme() {
        assertThat(ManagementUrlPattern.isValid("http://{host}:8161/console/jolokia"))
                .isTrue();
        assertThat(ManagementUrlPattern.isValid("http://broker:8161/console/jolokia"))
                .isFalse();
        assertThat(ManagementUrlPattern.isValid("tcp://{host}:61616")).isFalse();
        assertThat(ManagementUrlPattern.isValid(null)).isFalse();
    }

    @Test
    void aPatternThatCouldNameAnotherHostIsNotValid() {
        assertThat(ManagementUrlPattern.isValid("http://evil/?h={host}")).isFalse();
        assertThat(ManagementUrlPattern.isValid("http://{host}.evil.example:8161/jolokia"))
                .isFalse();
        assertThat(ManagementUrlPattern.isValid("http://evil.example/{host}")).isFalse();
        assertThat(ManagementUrlPattern.isValid("http://user:pw@{host}:8161/jolokia"))
                .isFalse();
        assertThat(ManagementUrlPattern.isValid("http://{host}:8161/a?b=1")).isFalse();
        assertThat(ManagementUrlPattern.isValid("http://{host}:8161/a#frag")).isFalse();
        assertThat(ManagementUrlPattern.isValid("http://{host}:8161/{host}")).isFalse();
        assertThat(ManagementUrlPattern.isValid("https://{host}")).isTrue();
        assertThat(ManagementUrlPattern.isValid("https://{host}:8443/console/jolokia"))
                .isTrue();
    }

    @Test
    void aSeedWithAnAccountInItIsRecognisedAndImpliesAPatternWithoutIt() {
        assertThat(ManagementUrlPattern.hasUserInfo("http://admin:secret@broker:8161/jolokia"))
                .isTrue();
        assertThat(ManagementUrlPattern.hasUserInfo("http://broker:8161/jolokia"))
                .isFalse();
        assertThat(ManagementUrlPattern.defaultFor("http://admin:secret@broker:8161/jolokia"))
                .isEqualTo("http://{host}:8161/jolokia");
    }

    @Test
    void aConnectorsHostIsEverythingBeforeItsPort() {
        assertThat(ManagementUrlPattern.connectorHost("artemis-backup:61616")).isEqualTo("artemis-backup");
        assertThat(ManagementUrlPattern.connectorHost("artemis-backup")).isEqualTo("artemis-backup");
    }
}
