package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The account's roles from {@code listUser}, and every way the broker can decline to give them (ADR-0177). */
class BrokerAccountRolesTest {

    private final BrokerAccountRoles accountRoles = new BrokerAccountRoles();

    /** A client that gets the broker search answered, then each given fixture in turn. */
    private JolokiaBrokerClient client(String url, String... fixtures) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body("search-broker.json"), MediaType.APPLICATION_JSON));
        for (String fixture : fixtures) {
            server.expect(requestTo(url))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return new JolokiaBrokerClient(builder.build(), url, new JsonMapper());
    }

    private static String body(String fixtureName) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixtureName).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTheRolesOfTheAccountSorted() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-ok:8161/jolokia", "list-user.json"), "artemis");

        assertThat(read.readable()).isTrue();
        assertThat(read.roles()).containsExactly("amq", "ops");
    }

    @Test
    void aLoginModuleThatCannotListUsersIsAReasonNotAFailure() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-ldap:8161/jolokia", "list-user-unsupported.json"), "artemis");

        assertThat(read.readable()).isFalse();
        assertThat(read.roles()).isEmpty();
        assertThat(read.unreadableReason())
                .isEqualTo("the broker's login module does not support listing users (LDAP and custom modules do not)");
    }

    @Test
    void anAccountWithoutManagementRightsSaysSo() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-denied:8161/jolokia", "list-user-refused.json"), "artemis");

        assertThat(read.unreadableReason()).isEqualTo("this account is not allowed to read the broker's users");
    }

    @Test
    void aUserTheBrokerListsNoRolesForIsUnreadable() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-empty:8161/jolokia", "list-user-empty.json"), "artemis");

        assertThat(read.readable()).isFalse();
        assertThat(read.unreadableReason()).isEqualTo("the broker lists no roles for artemis");
    }

    @Test
    void anAnonymousAccountIsNeverAskedAbout() {
        // listUser("") lists every user on the broker, so a blank name must not reach it.
        JolokiaBrokerClient neverCalled = new JolokiaBrokerClient(
                RestClient.builder().build(), "http://roles-anon:8161/jolokia", new JsonMapper());

        assertThat(accountRoles.read(neverCalled, " ").unreadableReason())
                .isEqualTo("Studio connects to the broker without an account");
        assertThat(accountRoles.read(neverCalled, null).readable()).isFalse();
    }
}
