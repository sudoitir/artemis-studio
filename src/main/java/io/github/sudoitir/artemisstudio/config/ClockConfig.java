package io.github.sudoitir.artemisstudio.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one seam that makes time testable where time is part of the answer
 * (ADR-0053).
 *
 * <p>Deliberately not a sweep of every {@code Instant.now()} in the codebase.
 * Injecting it everywhere would be a large refactor for no gain: most call sites
 * only stamp "when did this happen", and are indifferent to which clock said so.
 * The request-reply and clock-skew paths are different — there, a comparison
 * between two clocks is the whole computation — so those take the {@link Clock}
 * and can be driven from a test.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
