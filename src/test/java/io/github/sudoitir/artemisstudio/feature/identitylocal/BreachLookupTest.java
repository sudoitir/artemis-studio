package io.github.sudoitir.artemisstudio.feature.identitylocal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** SHA-1 of "password" is 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8. */
class BreachLookupTest {

    private static final String RANGE = "https://api.pwnedpasswords.com/range/5BAA6";
    private static final String SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();

    private static SettingsService settings(boolean on) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.bool(IdentityLocalSettings.BREACH_LOOKUP)).thenReturn(on);
        return settings;
    }

    @Test
    void sendsOnlyThePrefixAndRecognisesASuffixInTheRange() {
        server.expect(requestTo(RANGE))
                .andExpect(header("Add-Padding", "true"))
                .andRespond(withSuccess("00D4F6E8FA6EECAD2A3AA415EEC418D38EC:2\r\n" + SUFFIX + ":10437277\r\n", null));

        assertThat(new BreachLookup(settings(true), builder.build()).isBreached("password"))
                .isTrue();
        server.verify();
    }

    @Test
    void ignoresPaddingEntries() {
        server.expect(requestTo(RANGE)).andRespond(withSuccess(SUFFIX + ":0\r\n", null));

        assertThat(new BreachLookup(settings(true), builder.build()).isBreached("password"))
                .isFalse();
    }

    @Test
    void aSuffixNotInTheRangeIsNotBreached() {
        server.expect(requestTo(RANGE)).andRespond(withSuccess("00D4F6E8FA6EECAD2A3AA415EEC418D38EC:2\r\n", null));

        assertThat(new BreachLookup(settings(true), builder.build()).isBreached("password"))
                .isFalse();
    }

    @Test
    void failsOpenWhenTheServiceIsDown() {
        server.expect(requestTo(RANGE)).andRespond(withServerError());

        assertThat(new BreachLookup(settings(true), builder.build()).isBreached("password"))
                .isFalse();
    }

    @Test
    void makesNoRequestWhenSwitchedOff() {
        assertThat(new BreachLookup(settings(false), builder.build()).isBreached("password"))
                .isFalse();
        server.verify();
    }
}
