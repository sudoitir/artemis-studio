package io.github.sudoitir.artemisstudio.kernel.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.core.HeavyRequests;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** An export that stopped at a bound ends with a mark that says so, in each format. */
class AuditExportFormatTest {

    private static String truncated(AuditExportFormat format, HeavyRequests.Bound bound) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AuditExportFormat.Sink sink = format.open(out, JsonMapper.builder().build());
        sink.truncated(bound);
        sink.finish();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void jsonEndsWithAnElementNamingTheBound() {
        assertThat(truncated(AuditExportFormat.JSON, HeavyRequests.Bound.ROWS))
                .contains("\"truncated\":true")
                .contains("\"bound\":\"ROWS\"");
    }

    @Test
    void ndjsonEndsWithALineNamingTheBound() {
        assertThat(truncated(AuditExportFormat.NDJSON, HeavyRequests.Bound.TIME))
                .contains("\"truncated\":true")
                .contains("\"bound\":\"TIME\"");
    }

    @Test
    void csvEndsWithARowThatSaysTheFileIsIncomplete() {
        assertThat(truncated(AuditExportFormat.CSV, HeavyRequests.Bound.ROWS))
                .contains("# incomplete: the ROWS bound was reached");
    }
}
