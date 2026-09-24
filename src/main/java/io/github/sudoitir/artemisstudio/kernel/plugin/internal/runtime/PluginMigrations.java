package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ChangesetInfo;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.command.CommandScope;
import liquibase.command.core.ReleaseLocksCommandStep;
import liquibase.command.core.RollbackCommandStep;
import liquibase.command.core.TagCommandStep;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.UpdateSqlCommandStep;
import liquibase.command.core.helpers.DatabaseChangelogCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ZipResourceAccessor;

/**
 * Applies a plugin's own {@code db/changelog/plugin/<id>/changelog.xml} into its own schema
 * (design.md §3, task 6.7), from one dedicated connection out of the plugin's own pool, held for
 * the whole activation:
 *
 * <ol>
 *   <li>{@code pg_advisory_lock(hashtext('plugin:'||id))} — serializes concurrent activations of
 *       the same plugin (across a multi-instance deployment sharing one Postgres) without a
 *       schema-qualified lock table;
 *   <li>{@code CREATE SCHEMA IF NOT EXISTS};
 *   <li>Liquibase's own {@code release-locks}, then {@code tag pre-<version>}, then {@code update};
 *   <li>a guard: no new relation in {@code public} (schema conventions non-negotiable — a plugin's
 *       own tables live only in its own schema) and no foreign key from the plugin's schema into
 *       {@code public}. A violation rolls back to the pre-update tag (best effort) and always
 *       fails the activation with a clear message.
 * </ol>
 *
 * <p>{@link CommandScope} needs <em>both</em> {@code provideDependency(Database.class, db)}
 * <em>and</em> {@code addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db)} —
 * {@code DbUrlConnectionArgumentsCommandStep.validate()} still runs even with a Database already
 * provided, and requires the {@code url} argument unless the {@code DATABASE_ARG} slot is also set
 * directly (the spike found this).
 */
@org.springframework.stereotype.Component
public class PluginMigrations {

    private static final String CHANGELOG_PATH_TEMPLATE = "db/changelog/plugin/%s/changelog.xml";

    public record MigrationResult(String tag, List<ChangesetInfo> applied) {}

    /** Runs the full activation sequence described above. */
    public MigrationResult migrate(DataSource pluginPool, String schema, String pluginId, String version, Path jarPath)
            throws Exception {
        if (!hasChangelog(jarPath, pluginId)) {
            return new MigrationResult(null, List.of()); // a plugin with no data of its own
        }
        // Liquibase's FastCheckService caches "is this changelog already fully applied?" keyed by
        // (URL, schema) — a singleton meant to speed up repeated runs against a database that isn't
        // changing between them. That assumption breaks down here: a fresh schema's very first
        // update, with zero pending changesets (every plugin's Instant install/update runs one),
        // caches "up to date", and a later update against the SAME schema that genuinely does have
        // new changesets to apply is then wrongly fast-pathed as already-up-to-date and silently
        // skipped — reproduced in isolation and confirmed against this exact cache. Clearing it
        // before every migrate() forces a real check every time, which is the only correct behaviour
        // here: this JVM runs `update` against a great many different, independently-evolving plugin
        // schemas, never just one repeatedly unchanged database the way the cache assumes.
        liquibase.Scope.getCurrentScope()
                .getSingleton(liquibase.changelog.FastCheckService.class)
                .clearCache();
        try (Connection connection = pluginPool.getConnection()) {
            connection.setAutoCommit(true);
            acquireAdvisoryLock(connection, pluginId);
            try {
                executeUpdate(connection, "CREATE SCHEMA IF NOT EXISTS " + quoteIdent(schema));
                Database database = openDatabase(connection, schema);
                String tag = "pre-" + version;
                Set<String> publicBefore = publicRelations(connection);

                withResourceAccessor(jarPath, pluginId, accessor -> {
                    releaseLocks(database);
                    CommandScope tagCmd = new CommandScope(TagCommandStep.COMMAND_NAME);
                    tagCmd.addArgumentValue(TagCommandStep.TAG_ARG, tag);
                    provideDatabase(tagCmd, database);
                    tagCmd.execute();

                    CommandScope update = new CommandScope(UpdateCommandStep.COMMAND_NAME);
                    update.addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelogPath(pluginId));
                    provideDatabase(update, database);
                    update.execute();
                    return null;
                });

                Set<String> publicAfter = publicRelations(connection);
                publicAfter.removeAll(publicBefore);
                List<String> foreignKeysIntoPublic = foreignKeysFromSchemaIntoPublic(connection, schema);
                if (!publicAfter.isEmpty() || !foreignKeysIntoPublic.isEmpty()) {
                    String reason = violationMessage(publicAfter, foreignKeysIntoPublic);
                    try {
                        rollbackToTag(database, tag, jarPath, pluginId);
                    } catch (Exception rollbackFailure) {
                        throw new PluginMigrationException(
                                reason + " The activation could not even be rolled back to " + tag + ": "
                                        + rollbackFailure.getMessage(),
                                rollbackFailure);
                    }
                    throw new PluginMigrationException(reason + " Rolled back to " + tag + ".", null);
                }

                List<ChangesetInfo> applied = changesets(jarPath, pluginId);
                return new MigrationResult(tag, applied);
            } finally {
                releaseAdvisoryLock(connection, pluginId);
            }
        }
    }

    /** Rolls the plugin's schema back to a previously recorded tag, on a dedicated pool connection. */
    public void rollbackToTag(DataSource pluginPool, String schema, String tag, Path jarPath, String pluginId)
            throws Exception {
        if (!hasChangelog(jarPath, pluginId)) {
            return;
        }
        try (Connection connection = pluginPool.getConnection()) {
            connection.setAutoCommit(true);
            Database database = openDatabase(connection, schema);
            rollbackToTag(database, tag, jarPath, pluginId);
        }
    }

    private void rollbackToTag(Database database, String tag, Path jarPath, String pluginId) throws Exception {
        withResourceAccessor(jarPath, pluginId, accessor -> {
            CommandScope rollback = new CommandScope(RollbackCommandStep.COMMAND_NAME);
            rollback.addArgumentValue(DatabaseChangelogCommandStep.CHANGELOG_FILE_ARG, changelogPath(pluginId));
            rollback.addArgumentValue(RollbackCommandStep.TAG_ARG, tag);
            provideDatabase(rollback, database);
            rollback.execute();
            return null;
        });
    }

    /** The changesets not yet applied to {@code schema} — for the update review. */
    public List<ChangesetInfo> pendingChangesets(DataSource pluginPool, String schema, String pluginId, Path jarPath)
            throws Exception {
        if (!hasChangelog(jarPath, pluginId)) {
            return List.of();
        }
        try (Connection connection = pluginPool.getConnection()) {
            Database database = openDatabase(connection, schema);
            return withResourceAccessor(jarPath, pluginId, accessor -> {
                DatabaseChangeLog changeLog = parseChangeLog(pluginId, accessor);
                List<ChangeSet> unrun = new ArrayList<>();
                for (ChangeSet cs : changeLog.getChangeSets()) {
                    if (database.getRanChangeSet(cs) == null) {
                        unrun.add(cs);
                    }
                }
                return toInfos(unrun);
            });
        }
    }

    /** The SQL an update would run, without running it — for the review's "What changes" panel. */
    public String updateSql(DataSource pluginPool, String schema, String pluginId, Path jarPath) throws Exception {
        if (!hasChangelog(jarPath, pluginId)) {
            return "";
        }
        try (Connection connection = pluginPool.getConnection()) {
            Database database = openDatabase(connection, schema);
            return withResourceAccessor(jarPath, pluginId, accessor -> {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                CommandScope updateSql = new CommandScope(UpdateSqlCommandStep.COMMAND_NAME);
                updateSql.addArgumentValue(UpdateSqlCommandStep.CHANGELOG_FILE_ARG, changelogPath(pluginId));
                provideDatabase(updateSql, database);
                updateSql.setOutput(out);
                updateSql.execute();
                return withoutBookkeeping(out.toString(java.nio.charset.StandardCharsets.UTF_8));
            });
        }
    }

    /**
     * Only the plugin's own changes: Liquibase's generated SQL also creates, locks and records into
     * its {@code databasechangelog} tables, which says nothing an operator reviewing a plugin needs.
     * The output is blocks separated by blank lines, each led by a comment naming it.
     */
    static String withoutBookkeeping(String sql) {
        return java.util.Arrays.stream(sql.split("\\R\\s*\\R"))
                        .filter(block ->
                                !block.toLowerCase(java.util.Locale.ROOT).contains("databasechangelog"))
                        .filter(block -> block.lines().anyMatch(line -> !line.isBlank() && !line.startsWith("--")))
                        .map(String::strip)
                        .collect(java.util.stream.Collectors.joining("\n\n"))
                + "\n";
    }

    /** Whether every changeset in the plugin's changelog declares a rollback. */
    public boolean isReversible(Path jarPath, String pluginId) throws Exception {
        return changesets(jarPath, pluginId).stream().allMatch(ChangesetInfo::reversible);
    }

    // ---- internals ---------------------------------------------------------------------------

    /** A changelog is optional: a plugin that keeps no data of its own has none, and no schema. */
    public boolean hasChangelog(Path jarPath, String pluginId) throws java.io.IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            return jar.getJarEntry(changelogPath(pluginId)) != null;
        }
    }

    private List<ChangesetInfo> changesets(Path jarPath, String pluginId) throws Exception {
        if (!hasChangelog(jarPath, pluginId)) {
            return List.of();
        }
        return withResourceAccessor(
                jarPath,
                pluginId,
                accessor -> toInfos(parseChangeLog(pluginId, accessor).getChangeSets()));
    }

    private List<ChangesetInfo> toInfos(List<ChangeSet> changeSets) {
        List<ChangesetInfo> infos = new ArrayList<>();
        for (ChangeSet cs : changeSets) {
            boolean reversible = cs.hasCustomRollbackChanges()
                    || (cs.getRollback() != null
                            && !cs.getRollback().getChanges().isEmpty());
            infos.add(new ChangesetInfo(cs.getId(), cs.getAuthor(), reversible));
        }
        return infos;
    }

    private DatabaseChangeLog parseChangeLog(String pluginId, ZipResourceAccessor accessor) throws Exception {
        String path = changelogPath(pluginId);
        return Scope.child(
                Map.of(Scope.Attr.resourceAccessor.name(), accessor),
                () -> ChangeLogParserFactory.getInstance()
                        .getParser(path, accessor)
                        .parse(path, new ChangeLogParameters(), accessor));
    }

    private interface ScopedWork<T> {
        T run(ZipResourceAccessor accessor) throws Exception;
    }

    private <T> T withResourceAccessor(Path jarPath, String pluginId, ScopedWork<T> work) throws Exception {
        try (ZipResourceAccessor accessor = new ZipResourceAccessor(jarPath.toFile())) {
            return Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), accessor), () -> work.run(accessor));
        }
    }

    private String changelogPath(String pluginId) {
        return CHANGELOG_PATH_TEMPLATE.formatted(pluginId);
    }

    private Database openDatabase(Connection connection, String schema) throws liquibase.exception.DatabaseException {
        Database database =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        database.setDefaultSchemaName(schema);
        database.setLiquibaseSchemaName(schema);
        return database;
    }

    private void provideDatabase(CommandScope command, Database database) {
        command.provideDependency(Database.class, database);
        command.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database);
    }

    private void releaseLocks(Database database) throws liquibase.exception.CommandExecutionException {
        CommandScope releaseLocks = new CommandScope(ReleaseLocksCommandStep.COMMAND_NAME);
        provideDatabase(releaseLocks, database);
        releaseLocks.execute();
    }

    private void acquireAdvisoryLock(Connection connection, String pluginId) throws java.sql.SQLException {
        try (var ps = connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
            ps.setString(1, "plugin:" + pluginId);
            ps.execute();
        }
    }

    private void releaseAdvisoryLock(Connection connection, String pluginId) throws java.sql.SQLException {
        try (var ps = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, "plugin:" + pluginId);
            ps.execute();
        }
    }

    private void executeUpdate(Connection connection, String sql) throws java.sql.SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private Set<String> publicRelations(Connection connection) throws java.sql.SQLException {
        Set<String> relations = new HashSet<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT c.relname FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind IN ('r', 'v', 'm', 'S')
                          AND NOT c.relispartition
                        """)) {
            while (rs.next()) {
                relations.add(rs.getString(1));
            }
        }
        return relations;
    }

    private List<String> foreignKeysFromSchemaIntoPublic(Connection connection, String schema)
            throws java.sql.SQLException {
        List<String> violations = new ArrayList<>();
        try (var ps = connection.prepareStatement("""
                SELECT con.conname, cl.relname
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid
                JOIN pg_namespace clns ON clns.oid = cl.relnamespace
                JOIN pg_class fcl ON fcl.oid = con.confrelid
                JOIN pg_namespace fclns ON fclns.oid = fcl.relnamespace
                WHERE con.contype = 'f' AND clns.nspname = ? AND fclns.nspname = 'public'
                """)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    violations.add(rs.getString(1) + " (on " + rs.getString(2) + ")");
                }
            }
        }
        return violations;
    }

    private String violationMessage(Set<String> newPublicRelations, List<String> foreignKeysIntoPublic) {
        StringBuilder sb = new StringBuilder("The plugin's changelog violates the schema-confinement rule:");
        if (!newPublicRelations.isEmpty()) {
            sb.append(" created relation(s) in the shared public schema: ")
                    .append(newPublicRelations)
                    .append('.');
        }
        if (!foreignKeysIntoPublic.isEmpty()) {
            sb.append(" foreign key(s) reaching from the plugin's schema into public: ")
                    .append(foreignKeysIntoPublic)
                    .append('.');
        }
        return sb.toString();
    }

    private static String quoteIdent(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Thrown when an activation's own changelog cannot be applied, or violates schema confinement. */
    public static final class PluginMigrationException extends RuntimeException {
        public PluginMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
