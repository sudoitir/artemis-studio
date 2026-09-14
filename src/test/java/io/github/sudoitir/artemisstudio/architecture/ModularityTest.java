package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Module boundaries (ADR-0069): no dependency cycles between modules, no module reaching into
 * another's internals, and only the dependencies each module declares.
 */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(ArtemisStudioApplication.class).verify();
    }
}
