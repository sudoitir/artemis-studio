package io.github.sudoitir.artemisstudio.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.artemisstudio.app.StudioFeatures;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The permission catalogue is assembled from module descriptors (ADR-0070), so a
 * permission a module checks but never declares would be missing from the role
 * editor, and a typo would grant nothing. Checked from three directions: the
 * constants the backend checks, the literals the frontend gates on, and the
 * built-in role seeds.
 */
class PermissionCatalogueTest {

    private static final Set<String> CATALOGUE = StudioFeatures.descriptors().stream()
            .flatMap(d -> d.permissions().stream())
            .map(PermissionDef::action)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> RESOURCES =
            CATALOGUE.stream().map(a -> a.substring(0, a.indexOf(':'))).collect(Collectors.toUnmodifiableSet());

    private static final Pattern LITERAL = Pattern.compile("['\"]([a-z]+):([a-z*]+)['\"]");

    @Test
    void everyPermissionConstantTheBackendChecksIsCatalogued() throws IllegalAccessException {
        List<String> missing = new ArrayList<>();
        for (JavaClass c : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.sudoitir.artemisstudio")) {
            if (!c.getSimpleName().endsWith("Permissions")) {
                continue;
            }
            for (Field f : c.reflect().getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class) {
                    String value = (String) f.get(null);
                    if (!"*".equals(value) && !CATALOGUE.contains(value)) {
                        missing.add(c.getSimpleName() + "." + f.getName() + " = " + value);
                    }
                }
            }
        }
        assertThat(missing)
                .as("declare these in the owning module's descriptor")
                .isEmpty();
    }

    @Test
    void everyPermissionLiteralTheFrontendGatesOnIsCatalogued() throws IOException {
        assertThat(uncatalogued(Path.of("web", "src"), ".ts", ".tsx")).isEmpty();
    }

    @Test
    void builtInRoleSeedsHoldOnlyCataloguedPermissionsOrWildcards() throws IOException {
        assertThat(uncatalogued(Path.of("src", "main", "resources", "db", "changelog"), ".sql"))
                .isEmpty();
    }

    private static List<String> uncatalogued(Path root, String... extensions) throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p ->
                            Stream.of(extensions).anyMatch(e -> p.toString().endsWith(e)))
                    .toList()) {
                Matcher m = LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    String resource = m.group(1);
                    String action = resource + ":" + m.group(2);
                    if (RESOURCES.contains(resource) && !m.group(2).equals("*") && !CATALOGUE.contains(action)) {
                        bad.add(root.relativize(file) + ": " + action);
                    }
                }
            }
        }
        return bad;
    }
}
