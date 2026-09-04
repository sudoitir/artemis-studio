package io.github.sudoitir.artemisstudio.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Decodes {@code browse(int,int,String)} responses against the slice-0 fixtures. */
class MessageBrowserTest {

    private static final String URL = "http://broker:8161/console/jolokia";
    private static final String MBEAN = "org.apache.activemq.artemis:broker=\"primary\","
            + "component=addresses,address=\"PHASE3.SRC\",subcomponent=queues,"
            + "routing-type=\"anycast\",queue=\"PHASE3.SRC\"";

    private final JsonMapper mapper = new JsonMapper();
    private final MessageBrowser browser = new MessageBrowser();

    /** {@code batch()} wants a JSON array; wrap the single-op fixture plus a MessageCount entry. */
    private JolokiaBrokerClient client(String browseFixture, long messageCount) {
        String batch = "[" + body(browseFixture) + ",{\"value\":" + messageCount + ",\"status\":200}]";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(batch, MediaType.APPLICATION_JSON));
        return new JolokiaBrokerClient(builder.build(), URL, mapper);
    }

    private static String body(String fixture) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void decodesFourRowsWithTypedPropertyMapsFromAPlainArray() {
        BrowsePage page = browser.browse(client("browse.json", 4), MBEAN, 1, 50, "");

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.messages()).hasSize(4);

        BrowsedMessage first = page.messages().get(0);
        assertThat(first.messageId()).isEqualTo(127L);
        assertThat(first.type()).isEqualTo(3);
        assertThat(first.durable()).isTrue();
        assertThat(first.body()).isEqualTo("order body 1");
        assertThat(first.size()).isEqualTo(169L);
        assertThat(first.stringProperties()).containsEntry("orderId", "A-1").containsEntry("region", "eu");
        assertThat(first.propertyCount()).isEqualTo(2);
        assertThat(first.bodyTruncated()).isFalse();
    }

    @Test
    void flagsTheOversizedRowAsTruncatedWithAnObservedLimitNear256() {
        BrowsePage page = browser.browse(client("browse.json", 4), MBEAN, 1, 50, "");

        BrowsedMessage big = page.messages().get(3);
        assertThat(big.messageId()).isEqualTo(146L);
        assertThat(big.bodyTruncated()).isTrue();
        assertThat(big.observedLimitBytes()).isNotNull();
        assertThat(big.observedLimitBytes()).isBetween(200, 260);
        assertThat(big.stringProperties()).containsEntry("note", "oversized");
    }

    @Test
    void singleRowTruncatedFixtureAlsoTrips() {
        BrowsePage page = browser.browse(client("browse-truncated.json", 1), MBEAN, 1, 50, "orderId = 'BIG-1'");

        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().get(0).bodyTruncated()).isTrue();
    }

    @Test
    void mapsAnInvalidFilterToIllegalArgument() {
        assertThatThrownBy(() ->
                        browser.browse(client("browse-bad-filter.json", 0), MBEAN, 1, 50, "this is not a filter =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid message filter");
    }
}
