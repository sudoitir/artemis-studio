package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @Test
    void masksCredentialsInDetailAndTitle() {
        ProblemDetail problem = Problems.of(
                HttpStatus.BAD_GATEWAY,
                "broker-unreachable",
                "Failed for token=abc123",
                "Could not connect to tcp://bob:hunter2@broker:61616 with password=hunter2");

        assertThat(problem.getDetail())
                .isEqualTo("Could not connect to tcp://[redacted]@broker:61616 with password=[redacted]");
        assertThat(problem.getTitle()).isEqualTo("Failed for token=[redacted]");
    }
}
