package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The parts of the engine that need no database. */
class GateEngineTest {

    @Test
    void redactionReplacesEachPointedValueAndKeepsTheFormCanonical() {
        String canonical = "{\"a/b\":\"x\",\"list\":[\"one\",\"two\"],\"name\":\"n\",\"user\":{\"password\":\"p\"}}";

        String redacted = GateEngine.redact(canonical, Set.of("/user/password", "/list/1", "/a~1b", "/missing"));

        assertThat(redacted)
                .isEqualTo("{\"a/b\":\"[redacted]\",\"list\":[\"one\",\"[redacted]\"],\"name\":\"n\",\"user\":"
                        + "{\"password\":\"[redacted]\"}}");
        assertThat(GateEngine.redact(canonical, Set.of())).isEqualTo(canonical);
    }
}
