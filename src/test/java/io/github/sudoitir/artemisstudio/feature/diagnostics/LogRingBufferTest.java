package io.github.sudoitir.artemisstudio.feature.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The buffer stores lines already redacted and keeps only the most recent ones (ADR-0146). Other threads of the
 * test JVM log into the same buffer (a metrics registry that cannot reach its endpoint warns every second), so
 * each test looks for its own lines by a marker instead of assuming the first or last line is its own.
 */
class LogRingBufferTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogRingBufferTest.class);

    @Test
    void aLineIsStoredRedacted() {
        LogRingBuffer buffer = LogRingBuffer.attached();
        String marker = UUID.randomUUID().toString();
        String secret = "s3cret-" + UUID.randomUUID();

        LOG.warn(
                "connect failed {} password={}",
                marker,
                secret,
                new IllegalStateException("tcp://bob:" + secret + "@b:61616"));

        String stored = buffer.lines().stream()
                .filter(line -> line.contains(marker))
                .findFirst()
                .orElseThrow();
        assertThat(stored).contains("connect failed " + marker + " password=[redacted]", "tcp://[redacted]@b:61616");
        assertThat(String.join("", buffer.lines())).doesNotContain(secret);
        assertThat(LogRingBuffer.attached()).isSameAs(buffer);
    }

    @Test
    void onlyTheMostRecentLinesAreKept() {
        LogRingBuffer buffer = LogRingBuffer.attached();
        String marker = UUID.randomUUID().toString();

        for (int i = 0; i <= LogRingBuffer.CAPACITY; i++) {
            LOG.warn("line {} {}", marker, i);
        }

        List<String> lines = buffer.lines();
        assertThat(lines).hasSize(LogRingBuffer.CAPACITY);
        List<String> ours = lines.stream()
                .filter(line -> line.contains("line " + marker + " "))
                .toList();
        // At least CAPACITY lines came after the first one, so it is gone; the last one is still there.
        assertThat(ours).noneMatch(line -> line.contains("line " + marker + " 0\n"));
        assertThat(ours.getLast()).contains("line " + marker + " " + LogRingBuffer.CAPACITY);
    }
}
