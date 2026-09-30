package io.github.sudoitir.artemisstudio.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

/**
 * Boot configures logging once per JVM, so each configuration (Boot's default pattern, a custom pattern using every
 * alias, the log file, structured JSON) runs {@link Probe} in its own JVM and reads what it printed (ADR-0133).
 */
class LogRedactionTest {

    private static final String[] LEAKS = {"hunter2", "abc.def", "s3cret"};

    @Configuration
    static class Empty {}

    /** Starts Boot with the given arguments and logs a password, a bearer token and a stack trace with user-info. */
    public static class Probe {

        public static void main(String[] args) {
            var context = new SpringApplicationBuilder(Empty.class)
                    .web(WebApplicationType.NONE)
                    .run(args);
            Logger log = LoggerFactory.getLogger("io.github.sudoitir.artemisstudio.LogRedactionTest");
            log.warn("login failed password=hunter2 header Bearer abc.def");
            log.error("connect failed", new IllegalStateException("cannot reach tcp://bob:s3cret@broker:61616"));
            context.close();
        }
    }

    private static String run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    @Test
    void bootDefaultPatternIsMasked() throws Exception {
        assertThat(run())
                .contains("password=[redacted]", "Bearer [redacted]", "tcp://[redacted]@broker:61616")
                .doesNotContain(LEAKS);
    }

    @Test
    void everyAliasInACustomPatternIsMasked() throws Exception {
        String output = run("--logging.pattern.console=A[%m] B[%msg] C[%message] D[%ex] E[%exception] F[%throwable] "
                + "G[%xEx] H[%xException] I[%xThrowable] J[%redactedEx]%n");

        assertThat(output)
                .contains("A[login failed password=[redacted]", "B[login failed password=[redacted]")
                .contains("D[java.lang.IllegalStateException: cannot reach tcp://[redacted]@broker")
                .contains("G[java.lang.IllegalStateException: cannot reach tcp://[redacted]@broker")
                .doesNotContain(LEAKS);
    }

    @Test
    void theLogFileIsMaskedToo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("studio.log");
        run("--logging.file.name=" + file);

        assertThat(Files.readString(file))
                .contains("password=[redacted]", "tcp://[redacted]@broker:61616")
                .doesNotContain(LEAKS);
    }

    @Test
    void structuredJsonIsMasked() throws Exception {
        assertThat(run("--logging.structured.format.console=logstash"))
                .contains("\"message\":\"login failed password=[redacted]", "tcp://[redacted]@broker")
                .doesNotContain(LEAKS);
    }
}
