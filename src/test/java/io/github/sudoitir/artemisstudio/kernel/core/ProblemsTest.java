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

    @Test
    void aRefusedHeavyRequestNamesTheLimitAndWhoseItIs() {
        ProblemDetail own = Problems.heavyLimit(HeavyLimitException.perUser("audit exports", 1));
        ProblemDetail shared = Problems.heavyLimit(HeavyLimitException.installation("audit exports", 2));

        assertThat(own.getStatus()).isEqualTo(429);
        assertThat(own.getDetail()).contains("1 audit exports");
        assertThat(own.getProperties())
                .containsEntry("endpoint", "audit exports")
                .containsEntry("cap", 1)
                .containsEntry("installation", false)
                .containsEntry("retryAfter", Problems.HEAVY_RETRY_AFTER_SECONDS);
        assertThat(shared.getProperties()).containsEntry("installation", true).containsEntry("cap", 2);
    }

    @Test
    void aHeavyReadStoppedAtItsTimeBoundNamesTheBound() {
        ProblemDetail problem = Problems.heavyTimeout(
                new HeavyTimeoutException("audit history reads", java.time.Duration.ofSeconds(30)));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).contains("30 seconds").startsWith("Audit history reads");
        assertThat(problem.getProperties()).containsEntry("bound", HeavyRequests.Bound.TIME);
    }
}
