package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Module boundaries (ADR-0069): no dependency cycles between modules, and no module reaching into
 * another's internals.
 *
 * <p>One edge is tolerated until task 10.8 moves the recommended configuration into the
 * registration slot: a cluster registration preview still embeds broker configuration
 * recommendations, so {@code platform.clusters} depends on {@code feature.brokerconfig}, and that edge
 * and every cycle through it are reported. Only those violations are skipped.
 */
class ModularityTest {

    /** A cycle through the edge, or the edge itself: clusters using a broker configuration type. */
    private static final Pattern REGISTRATION_RECOMMENDATIONS = Pattern.compile(
            "Slice [\\w.]*platform\\.clusters ->\\s+Slice [\\w.]*feature\\.brokerconfig\\b"
                    + "|Module '[\\w.]*platform\\.clusters' depends on non-exposed type [\\w.$]*feature\\.brokerconfig\\.");

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(ArtemisStudioApplication.class)
                .detectViolations()
                .filter(violation -> !REGISTRATION_RECOMMENDATIONS
                        .matcher(violation.getMessage())
                        .find())
                .throwIfPresent();
    }
}
