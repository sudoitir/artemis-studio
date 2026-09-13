package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Module boundaries (ADR-0069). Report mode while the layered packages move into
 * kernel/platform/feature modules: violations are printed, not failed on.
 */
class ModularityTest {

    @Test
    void reportsModuleViolations() {
        var modules = ApplicationModules.of(ArtemisStudioApplication.class);
        modules.forEach(System.out::println);
        System.out.println(modules.detectViolations().getMessage());
    }
}
