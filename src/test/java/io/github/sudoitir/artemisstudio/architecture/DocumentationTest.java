package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Writes the module diagrams and canvases into {@code docs/modules/} (ADR-0069), so the documented
 * module graph is the one {@link ModularityTest} verifies. Committed like {@code openapi.json}: a
 * module or dependency change shows up in the diff of the change that made it.
 */
class DocumentationTest {

    @Test
    void writesModuleDocumentation() {
        ApplicationModules modules = ApplicationModules.of(ArtemisStudioApplication.class);
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder("docs/modules"))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
