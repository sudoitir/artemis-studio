package io.github.sudoitir.artemisstudio.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/** {@code logback-spring.xml} is only read when Boot initialises logging, hence a (tiny) Boot context. */
@SpringBootTest(classes = LogRedactionTest.Empty.class)
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTest {

    private static final Logger LOG = LoggerFactory.getLogger("io.github.sudoitir.artemisstudio.LogRedactionTest");

    @Configuration
    static class Empty {}

    @Test
    void messageAndStackTraceAreMasked(CapturedOutput output) {
        LOG.warn("login failed password=hunter2 header Bearer abc.def");
        LOG.error("connect failed", new IllegalStateException("cannot reach tcp://bob:s3cret@broker:61616"));

        assertThat(output.getAll())
                .contains("password=[redacted]", "Bearer [redacted]", "tcp://[redacted]@broker:61616")
                .doesNotContain("hunter2", "abc.def", "s3cret");
    }
}
