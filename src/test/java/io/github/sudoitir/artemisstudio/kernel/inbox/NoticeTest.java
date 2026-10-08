package io.github.sudoitir.artemisstudio.kernel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.inbox.Notice.Severity;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class NoticeTest {

    private static Notice withLink(String link) {
        return new Notice("approval", Severity.INFO, "Title", null, link, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/approvals/3", "/clusters/1?tab=a#b", "/a"})
    void aPathInsideStudioIsAccepted(String link) {
        assertThatCode(() -> withLink(link)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.example/x",
                "//evil.example/x",
                "/\\evil.example",
                "/a\\b",
                "/a//b",
                "javascript:alert(1)",
                "approvals/3",
                "/",
                "/ x",
                "/a\nb"
            })
    void anythingElseIsRefused(String link) {
        assertThatThrownBy(() -> withLink(link))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link");
    }

    @Test
    void titleAndBodyAreBounded() {
        assertThatCode(() -> new Notice(
                        "approval",
                        Severity.INFO,
                        "t".repeat(Notice.MAX_TITLE),
                        "b".repeat(Notice.MAX_BODY),
                        null,
                        null,
                        null,
                        null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new Notice(
                        "approval", Severity.INFO, "t".repeat(Notice.MAX_TITLE + 1), null, null, null, null, null))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new Notice("approval", Severity.INFO, " ", null, null, null, null, null))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new Notice(
                        "approval", Severity.INFO, "t", "b".repeat(Notice.MAX_BODY + 1), null, null, null, null))
                .hasMessageContaining("body");
    }

    @Test
    void kindSeverityAndTtlAreChecked() {
        assertThatThrownBy(() -> new Notice("Bad Kind", Severity.INFO, "t", null, null, null, null, null))
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> new Notice("approval", null, "t", null, null, null, null, null))
                .hasMessageContaining("severity");
        assertThatThrownBy(() -> new Notice("approval", Severity.INFO, "t", null, null, null, null, Duration.ZERO))
                .hasMessageContaining("ttl");
        assertThat(Severity.WARNING.wire()).isEqualTo("warning");
    }

    @Test
    void dataLargerThanTheTableAcceptsIsRefused() {
        InboxService service = new InboxService(null, new ObjectMapper(), null, null, null);

        assertThat(service.json(Map.of("a", "b"))).isEqualTo("{\"a\":\"b\"}");
        assertThat(service.json(Map.of())).isNull();
        Map<String, String> tooBig = Map.of("a", "x".repeat(Notice.MAX_DATA_BYTES));
        assertThatThrownBy(() -> service.json(tooBig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data");
    }
}
