package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Fact;
import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Severity;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoticeMessageTest {

    @Test
    void anInAppPathIsAccepted() {
        NoticeMessage n = new NoticeMessage("Approval requested", null, Severity.INFO, null, "/approvals/3?tab=a");

        assertThat(n.url()).isEqualTo("/approvals/3?tab=a");
        assertThat(n.facts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"https://evil.example.com/x", "//evil.example.com", "/a//b", "/a\\b", "approvals/3", "/", "/\n"})
    void anythingButAnInAppPathIsRefused(String url) {
        assertThatThrownBy(() -> new NoticeMessage("t", null, Severity.INFO, null, url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLimitsAreEnforced() {
        String longBody = "x".repeat(2001);
        String longValue = "v".repeat(501);
        assertThatThrownBy(() -> new NoticeMessage(" ", null, Severity.INFO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeMessage("t", longBody, Severity.INFO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeMessage("t", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        List<Fact> tooMany =
                IntStream.range(0, 11).mapToObj(i -> new Fact("l" + i, "v")).toList();
        assertThatThrownBy(() -> new NoticeMessage("t", null, Severity.INFO, tooMany, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fact("l", longValue)).isInstanceOf(IllegalArgumentException.class);
    }
}
