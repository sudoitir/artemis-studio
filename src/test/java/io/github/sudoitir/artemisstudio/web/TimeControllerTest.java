package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.web.dto.TimeViews.TimeView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The whole point of taking the {@link Clock} bean rather than calling
 * {@code Instant.now()} is that the reading can be driven from a test. No Spring
 * context: the controller has no collaborator but the clock.
 */
class TimeControllerTest {

    private static final Instant FIXED = Instant.parse("2026-09-07T10:15:30.123Z");

    @Test
    void reportsTheInjectedClockInEpochMillis() {
        ResponseEntity<TimeView> res = new TimeController(Clock.fixed(FIXED, ZoneOffset.UTC)).now();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().nowMs()).isEqualTo(FIXED.toEpochMilli());
    }

    @Test
    void isNeverCached() {
        ResponseEntity<TimeView> res = new TimeController(Clock.fixed(FIXED, ZoneOffset.UTC)).now();

        // A cached clock reading teaches the client an offset that grows by exactly
        // the age of the cache entry, which is worse than having no reading at all.
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }
}
