package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.web.dto.TimeViews.TimeView;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What time Studio thinks it is.
 *
 * <p>The client measures its own offset against this and computes every duration,
 * expiry and query window on Studio's timeline instead of the workstation's — the
 * browser-side counterpart to the broker-clock normalisation in ADR-0053. An
 * operator's laptop running four minutes fast otherwise inflates every age on the
 * screen by four minutes and asks for metric windows the server has no samples for.
 *
 * <p>Takes the {@link Clock} bean rather than calling {@code Instant.now()}: this is
 * exactly the case that seam exists for, and it is what makes the reading testable.
 *
 * <p>The handler is deliberately trivial — no service, no audit event, nothing to
 * fan out to a broker. It is on the hot path of every session and must stay cheap.
 */
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimeController {

    private final Clock clock;

    /**
     * {@code no-store}, because a cached clock reading is worse than none: it would
     * teach the client an offset that grows by exactly the age of the cache entry.
     */
    @GetMapping
    public ResponseEntity<TimeView> now() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TimeView(clock.millis()));
    }
}
