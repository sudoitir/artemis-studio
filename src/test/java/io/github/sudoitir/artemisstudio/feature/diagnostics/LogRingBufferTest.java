package io.github.sudoitir.artemisstudio.feature.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The buffer stores lines already redacted and keeps only the most recent ones (ADR-0146). */
class LogRingBufferTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogRingBufferTest.class);

    @Test
    void aLineIsStoredRedacted() {
        LogRingBuffer buffer = LogRingBuffer.attached();
        String secret = "s3cret-" + UUID.randomUUID();

        LOG.warn("connect failed password={}", secret, new IllegalStateException("tcp://bob:" + secret + "@b:61616"));

        String last = buffer.lines().getLast();
        assertThat(last).contains("connect failed password=[redacted]", "tcp://[redacted]@b:61616");
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

        assertThat(buffer.lines()).hasSize(LogRingBuffer.CAPACITY);
        assertThat(buffer.lines().getFirst()).contains("line " + marker + " 1");
        assertThat(buffer.lines().getLast()).contains("line " + marker + " " + LogRingBuffer.CAPACITY);
    }
}
