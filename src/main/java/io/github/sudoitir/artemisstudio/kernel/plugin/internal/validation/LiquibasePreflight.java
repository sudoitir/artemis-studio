package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ZipResourceAccessor;

/**
 * Parses the plugin's own {@code db/changelog/plugin/<id>/changelog.xml} with Liquibase, from
 * the jar, without a database connection (design.md §3, task 5.4). A jar is already a zip, so
 * {@link ZipResourceAccessor} reads it the same way {@link liquibase.resource.JarResourceAccessor}
 * would, without needing a bespoke {@code ResourceAccessor}. Parsing a changelog never runs SQL
 * and never touches a database — it only builds the in-memory {@link DatabaseChangeLog} model.
 */
final class LiquibasePreflight {

    private final PluginDescriptor descriptor;

    LiquibasePreflight(PluginDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    List<ChangesetInfo> check(JarFile jar, List<String> entryNames, List<Violation> violations) {
        String changelogPath = "db/changelog/plugin/" + descriptor.id() + "/changelog.xml";
        if (!entryNames.contains(changelogPath)) {
            return List.of();
        }
        File jarOnDisk = new File(jar.getName());
        try (ZipResourceAccessor accessor = new ZipResourceAccessor(jarOnDisk)) {
            DatabaseChangeLog changeLog = Scope.child(
                    Map.of(Scope.Attr.resourceAccessor.name(), accessor),
                    () -> ChangeLogParserFactory.getInstance()
                            .getParser(changelogPath, accessor)
                            .parse(changelogPath, new ChangeLogParameters(), accessor));
            List<ChangesetInfo> infos = new ArrayList<>();
            for (ChangeSet changeSet : changeLog.getChangeSets()) {
                if (!changeSet.isRunInTransaction()) {
                    violations.add(new Violation(
                            "changelog-run-in-transaction-false",
                            "Changeset %s:%s sets runInTransaction:false."
                                    .formatted(changeSet.getAuthor(), changeSet.getId()),
                            "Remove runInTransaction:false; every changeset runs inside the activation transaction."));
                }
                boolean reversible = changeSet.hasCustomRollbackChanges()
                        || (changeSet.getRollback() != null
                                && !changeSet.getRollback().getChanges().isEmpty());
                infos.add(new ChangesetInfo(changeSet.getId(), changeSet.getAuthor(), reversible));
            }
            return infos;
        } catch (Exception e) {
            violations.add(new Violation(
                    "changelog-invalid",
                    "The plugin's changelog could not be parsed: " + e.getMessage(),
                    "Fix %s; run `mvn liquibase:validate` locally against it.".formatted(changelogPath)));
            return List.of();
        }
    }
}
