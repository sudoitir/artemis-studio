package io.github.sudoitir.artemisstudio.architecture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Every table belongs to exactly one module (task 7.6, ADR-0072): the module whose changelog
 * creates it, whose {@code internal.persistence} package maps it, and whose foreign keys point
 * only at its own tables or at those of a module included earlier in the master changelog.
 * That Hibernate's mapping matches the schema is enforced separately, by
 * {@code ddl-auto=validate} in every integration-test context.
 */
class SchemaOwnershipTest {

    private static final String ROOT = "io.github.sudoitir.artemisstudio";
    private static final Pattern INCLUDE =
            Pattern.compile("<include file=\"db/changelog/([a-z]+/[a-z]+)/changelog\\.xml\"");
    private static final Pattern CREATE = Pattern.compile("^CREATE TABLE (\\w+)", Pattern.MULTILINE);
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "ALTER TABLE ONLY (\\w+)\\s+ADD CONSTRAINT (\\w+) FOREIGN KEY \\([^)]*\\) REFERENCES (\\w+)\\(");

    /** Module changelog directories, in the order the master changelog includes them. */
    private static List<String> modules() throws IOException {
        String master = new ClassPathResource("db/changelog/db.changelog-master.xml").getContentAsString(UTF_8);
        return INCLUDE.matcher(master).results().map(m -> m.group(1)).toList();
    }

    private static String sqlOf(String module) throws IOException {
        StringBuilder sql = new StringBuilder();
        for (Resource changeset : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/changelog/" + module + "/changes/*.sql")) {
            sql.append(changeset.getContentAsString(UTF_8)).append('\n');
        }
        return sql.toString();
    }

    /** Table name to the module that creates it. */
    private static Map<String, String> owners() throws IOException {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String module : modules()) {
            for (MatchResult table : CREATE.matcher(sqlOf(module)).results().toList()) {
                String previous = owners.put(table.group(1), module);
                assertThat(previous)
                        .as("%s is created by both %s and %s", table.group(1), previous, module)
                        .isNull();
            }
        }
        return owners;
    }

    @Test
    void everyTableIsCreatedByExactlyOneModule() throws IOException {
        assertThat(modules()).hasSizeGreaterThan(1);
        assertThat(owners()).containsKeys("app_user", "audit_event", "cluster", "alert_rule");
    }

    @Test
    void everyEntityLivesInThePersistencePackageOfTheModuleThatOwnsItsTable() throws IOException {
        Map<String, String> owners = owners();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> violations = new ArrayList<>();
        var entities = scanner.findCandidateComponents(ROOT);
        for (BeanDefinition entity : entities) {
            Class<?> type = ClassUtils.resolveClassName(entity.getBeanClassName(), null);
            String table = type.getAnnotation(Table.class).name();
            String owner = owners.get(table);
            String expected = owner == null
                    ? "(no module creates it)"
                    : ROOT + "." + owner.replace('/', '.') + ".internal.persistence";
            if (!type.getPackageName().equals(expected)) {
                violations.add("%s maps %s, expected in %s".formatted(type.getName(), table, expected));
            }
        }
        assertThat(entities).isNotEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    void foreignKeysPointOnlyAtTheSameOrAnEarlierModule() throws IOException {
        List<String> order = modules();
        Map<String, String> owners = owners();
        List<String> violations = new ArrayList<>();
        for (String module : order) {
            for (MatchResult fk : FOREIGN_KEY.matcher(sqlOf(module)).results().toList()) {
                String target = owners.get(fk.group(3));
                boolean ownTable = module.equals(owners.get(fk.group(1)));
                if (!ownTable || target == null || order.indexOf(target) > order.indexOf(module)) {
                    violations.add("%s in %s: %s -> %s (owned by %s)"
                            .formatted(fk.group(2), module, fk.group(1), fk.group(3), target));
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
