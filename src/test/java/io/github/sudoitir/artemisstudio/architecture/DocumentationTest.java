package io.github.sudoitir.artemisstudio.architecture;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Writes the module diagrams and canvases into {@code docs/modules/} (ADR-0069), so the documented
 * module graph is the one {@link ModularityTest} verifies. Committed like {@code openapi.json}: a
 * module or dependency change shows up in the diff of the change that made it.
 */
class DocumentationTest {

    private static final Path OUTPUT = Path.of("docs", "modules");

    @Test
    void writesModuleDocumentation() throws IOException {
        ApplicationModules modules = ApplicationModules.of(ArtemisStudioApplication.class);
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(OUTPUT.toString()))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
        try (Stream<Path> diagrams = Files.list(OUTPUT)) {
            for (Path diagram :
                    diagrams.filter(p -> p.toString().endsWith(".puml")).toList()) {
                Files.write(diagram, sortRelations(Files.readAllLines(diagram)));
            }
        }
    }

    /**
     * The documenter emits relations in no fixed order, which would rewrite every diagram on every
     * run. Each run of consecutive {@code Rel(} lines is sorted, so a diagram changes only when the
     * module graph does.
     */
    private static List<String> sortRelations(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        List<String> relations = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("Rel(")) {
                relations.add(line);
                continue;
            }
            relations.sort(null);
            out.addAll(relations);
            relations.clear();
            out.add(line);
        }
        relations.sort(null);
        out.addAll(relations);
        return out;
    }
}
