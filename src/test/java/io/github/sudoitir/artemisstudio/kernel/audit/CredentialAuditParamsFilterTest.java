package io.github.sudoitir.artemisstudio.kernel.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialAuditParamsFilterTest {

    private final CredentialAuditParamsFilter filter = new CredentialAuditParamsFilter();

    @Test
    void masksCredentialNamedParametersAtEveryDepth() {
        Map<String, Object> out = filter.filter(Map.of(
                "password",
                "hunter2",
                "name",
                "orders",
                "bridge",
                Map.of("apiKey", "k-123", "queue", "q"),
                "list",
                List.of(Map.of("secret", "s"), "plain")));

        assertThat(out).containsEntry("password", "[redacted]");
        assertThat(out).containsEntry("name", "orders");
        assertThat(out).containsEntry("bridge", Map.of("apiKey", "[redacted]", "queue", "q"));
        assertThat(out).containsEntry("list", List.of(Map.of("secret", "[redacted]"), "plain"));
    }

    @Test
    void masksCredentialShapedStrings() {
        Map<String, Object> out = filter.filter(Map.of("url", "tcp://bob:pw@broker:61616", "note", "token=abc"));

        assertThat(out).containsEntry("url", "tcp://[redacted]@broker:61616");
        assertThat(out).containsEntry("note", "token=[redacted]");
    }
}
