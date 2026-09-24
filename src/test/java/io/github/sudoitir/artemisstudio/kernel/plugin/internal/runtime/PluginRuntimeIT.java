package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End to end against the real application context and a real Postgres (task 6.11's criteria, for
 * the slice this session owns — 6.1/6.2/6.3/6.7): builds a plugin jar with {@link PluginJarBuilder}
 * at test time, migrates it into its own schema, activates it, and calls it through
 * {@link io.github.sudoitir.artemisstudio.kernel.plugin.web.PluginGateway} with real MockMvc (the
 * main application's own security filter chain included).
 */
class PluginRuntimeIT extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginMigrations migrations;

    @Autowired
    PluginDescriptorParser descriptorParser;

    private MockMvc mvc;
    private PluginRuntime activeRuntime;

    @AfterEach
    void cleanUp() {
        if (activeRuntime != null) {
            activeRuntime.close();
            registry.remove(activeRuntime.id());
        }
    }

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = webAppContextSetup(webContext)
                    .apply(
                            org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                                    .springSecurity())
                    .build();
        }
        return mvc;
    }

    private UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        StudioPrincipal principal = new StudioPrincipal(
                null, "plugin-caller", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private PluginDescriptor descriptorOf(Path jar) throws Exception {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            byte[] json = jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                    .readAllBytes();
            return descriptorParser.parse(json);
        }
    }

    private PluginJarBuilder notesJar(String version) {
        return new PluginJarBuilder("acme-notes")
                .descriptorField("version", version)
                .descriptorField("basePackage", "com.acme.notes")
                .descriptorField("configuration", "com.acme.notes.PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <include file="changes/0001-note.sql" relativeToChangelogFile="true"/>
                        </databaseChangeLog>
                        """)
                .entry("db/changelog/plugin/acme-notes/changes/0001-note.sql", """
                        --liquibase formatted sql

                        --changeset acme:0001-note
                        CREATE TABLE note (
                            id uuid NOT NULL PRIMARY KEY,
                            text text
                        );
                        --rollback DROP TABLE note;
                        """)
                .source("com.acme.notes.PluginConfig", """
                        package com.acme.notes;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        @ComponentScan(basePackages = "com.acme.notes")
                        public class PluginConfig {}
                        """)
                .source("com.acme.notes.NoteEntity", """
                        package com.acme.notes;
                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import jakarta.persistence.Table;
                        import java.util.UUID;
                        @Entity
                        @Table(name = "note")
                        public class NoteEntity {
                            @Id private UUID id;
                            private String text;
                            public NoteEntity() {}
                            public NoteEntity(UUID id, String text) { this.id = id; this.text = text; }
                            public UUID getId() { return id; }
                            public String getText() { return text; }
                        }
                        """)
                .source("com.acme.notes.NotesService", """
                        package com.acme.notes;
                        import jakarta.persistence.EntityManager;
                        import jakarta.persistence.PersistenceContext;
                        import java.util.UUID;
                        import org.springframework.stereotype.Service;
                        import org.springframework.transaction.annotation.Transactional;
                        @Service
                        public class NotesService {
                            @PersistenceContext
                            private EntityManager em;
                            @Transactional
                            public String create(String text) {
                                NoteEntity e = new NoteEntity(UUID.randomUUID(), text);
                                em.persist(e);
                                return e.getId().toString();
                            }
                        }
                        """)
                .source("com.acme.notes.NotesController", """
                        package com.acme.notes;
                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1/p/acme-notes")
                        public class NotesController {
                            private final NotesService service;
                            public NotesController(NotesService service) { this.service = service; }
                            @PostMapping("/notes")
                            public String create(@RequestParam String text) { return service.create(text); }
                            @GetMapping("/secure")
                            @PreAuthorize("@perm.can('acme-notes:read')")
                            public String secure() { return "granted"; }
                            @GetMapping("/version")
                            public String version() { return "%s"; }
                        }
                        """.formatted(version));
    }

    @Test
    void migratesIntoItsOwnSchemaAndEnforcesPreAuthorize() throws Exception {
        Path jar = notesJar("1.0.0").build();
        PluginDescriptor descriptor = descriptorOf(jar);
        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set("acme-notes", new PluginRuntimeRegistry.Active(activeRuntime));

        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='plugin_acme_notes' AND tablename='note'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='note'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }

        mvc().perform(MockMvcRequestBuilders.get("/api/v1/p/acme-notes/secure").with(authentication(callerWith())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
        mvc().perform(MockMvcRequestBuilders.get("/api/v1/p/acme-notes/secure")
                        .with(authentication(callerWith("acme-notes:read"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("granted"));

        mvc().perform(
                        MockMvcRequestBuilders.post("/api/v1/p/acme-notes/notes")
                                .with(authentication(callerWith()))
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .param("text", "hi"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
    }

    @Test
    void answers404ForAnUninstalledPlugin() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/api/v1/p/no-such-plugin/version")
                        .with(authentication(callerWith())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type")
                        .value(org.hamcrest.Matchers.endsWith("feature-disabled")));
    }

    @Test
    void answers503WhileUpdating() throws Exception {
        registry.set("acme-notes", new PluginRuntimeRegistry.Updating(2));
        try {
            mvc().perform(MockMvcRequestBuilders.get("/api/v1/p/acme-notes/version")
                            .with(authentication(callerWith())))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Retry-After", "2"));
        } finally {
            registry.remove("acme-notes");
        }
    }

    @Test
    void swapsVersionsUnderConcurrentTrafficWithZeroFailures() throws Exception {
        Path jarV1 = notesJar("1.0.0").build();
        PluginRuntime v1 = runtimeFactory.activate(descriptorOf(jarV1), jarV1, webContext.getServletContext());
        registry.set("acme-notes", new PluginRuntimeRegistry.Active(v1));

        AtomicBoolean failed = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> inflight = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            inflight.add(pool.submit(() -> {
                try {
                    String body = mvc().perform(MockMvcRequestBuilders.get("/api/v1/p/acme-notes/version")
                                    .with(authentication(callerWith())))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                    if (!"1.0.0".equals(body) && !"2.0.0".equals(body)) {
                        failed.set(true);
                    }
                } catch (Exception e) {
                    failed.set(true);
                }
            }));
        }

        Path jarV2 = notesJar("2.0.0").build();
        PluginRuntime v2 = runtimeFactory.activate(descriptorOf(jarV2), jarV2, webContext.getServletContext());
        registry.set("acme-notes", new PluginRuntimeRegistry.Active(v2)); // the swap: one reference set

        for (Future<?> f : inflight) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(failed.get())
                .as("no in-flight request should fail during the swap")
                .isFalse();

        v1.close();
        activeRuntime = v2;
    }

    @Test
    void unloadCollectsTheClassloader() throws Exception {
        WeakReference<ClassLoader> loaderRef = activateAndCloseReturningWeakRef();

        boolean collected = false;
        for (int i = 0; i < 20 && !collected; i++) {
            System.gc();
            Thread.sleep(200);
            collected = loaderRef.get() == null;
        }
        if (!collected) {
            ClassLoader stillThere = loaderRef.get();
            boolean pinnedByThread = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getContextClassLoader() == stillThere) {
                    pinnedByThread = true;
                    System.err.println("[LEAK] PINNED BY THREAD: " + t.getName() + " state=" + t.getState());
                    for (StackTraceElement el : t.getStackTrace()) {
                        System.err.println("[LEAK]     at " + el);
                    }
                }
            }
            if (!pinnedByThread) {
                String path = "target/plugin-leak.hprof";
                new java.io.File(path).delete();
                java.lang.management.ManagementFactory.getPlatformMBeanServer()
                        .invoke(
                                new javax.management.ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                                "dumpHeap",
                                new Object[] {path, true},
                                new String[] {"java.lang.String", "boolean"});
                System.err.println("[LEAK] no live thread is pinned; heap dump written to " + path);
            }
        }
        assertThat(collected)
                .as("the plugin classloader must be collected after unload")
                .isTrue();
    }

    /**
     * The loader of a plugin that actually served requests — JSON built from its own record type,
     * a {@code @PreAuthorize} expression reading a parameter, a JPA query — is collected too.
     * Activating and closing alone leaves every request-time cache untouched.
     */
    @Test
    void anExercisedPluginIsCollectedAfterUnload() throws Exception {
        WeakReference<ClassLoader> loaderRef = activateExerciseAndClose();
        boolean collected = false;
        for (int i = 0; i < 20 && !collected; i++) {
            System.gc();
            Thread.sleep(200);
            collected = loaderRef.get() == null;
        }
        if (!collected) {
            String path = "target/plugin-leak-exercised.hprof";
            new java.io.File(path).delete();
            java.lang.management.ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                            new javax.management.ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                            "dumpHeap",
                            new Object[] {path, true},
                            new String[] {"java.lang.String", "boolean"});
            System.err.println("[LEAK] heap dump written to " + path);
        }
        assertThat(collected)
                .as("an exercised plugin's classloader must be collected after unload")
                .isTrue();
    }

    private WeakReference<ClassLoader> activateExerciseAndClose() throws Exception {
        Path jar = notesJar("1.0.0")
                .source("com.acme.notes.NoteView", """
                        package com.acme.notes;
                        public record NoteView(String id, String text, java.util.List<String> tags) {}
                        """)
                .source("com.acme.notes.ViewsController", """
                        package com.acme.notes;
                        import java.util.List;
                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1/clusters/{clusterId}/p/acme-notes")
                        public class ViewsController {
                            @GetMapping("/views/{name}")
                            @PreAuthorize("@perm.can(#clusterId, 'acme-notes:read') && #name != 'x'")
                            public List<NoteView> views(@PathVariable java.util.UUID clusterId, @PathVariable String name) {
                                return List.of(new NoteView(name, "t", List.of("a")));
                            }
                        }
                        """)
                .build();
        PluginRuntime runtime = runtimeFactory.activate(descriptorOf(jar), jar, webContext.getServletContext());
        registry.set("acme-notes", new PluginRuntimeRegistry.Active(runtime));
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(runtime.classLoader());
        for (int i = 0; i < 3; i++) {
            mvc().perform(MockMvcRequestBuilders.get(
                                    "/api/v1/clusters/{c}/p/acme-notes/views/n{i}", java.util.UUID.randomUUID(), i)
                            .with(authentication(callerWith("acme-notes:read"))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isOk());
        }
        mvc().perform(
                        MockMvcRequestBuilders.post("/api/v1/p/acme-notes/notes")
                                .with(authentication(callerWith()))
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .param("text", "hi"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        registry.remove("acme-notes");
        runtime.close();
        return loaderRef;
    }

    /**
     * Isolated in its own frame, deliberately: a local variable a test method itself nulls out is
     * not guaranteed to be gone from that method's own stack frame at the exact instant a heap dump
     * is taken (slot reuse and debug-line liveness can keep an old value "in scope" past the source
     * line that reassigned it) — the JVM reports that stale slot as a {@code ROOT_JAVA_FRAME}, which
     * a heap-dump walk in this same investigation caught pinning the plugin's EMF proxy this way.
     * Returning only the {@link WeakReference} means this method's whole frame — and every local
     * variable it held — is off the stack before the caller starts checking for collection.
     */
    private WeakReference<ClassLoader> activateAndCloseReturningWeakRef() throws Exception {
        Path jar = notesJar("1.0.0").build();
        PluginRuntime runtime = runtimeFactory.activate(descriptorOf(jar), jar, webContext.getServletContext());
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(runtime.classLoader());
        runtime.close();
        return loaderRef;
    }

    @Test
    void migrationRefusesAPublicRelationAndAForeignKeyIntoPublic() throws Exception {
        Path badTable = new PluginJarBuilder("bad-public")
                .descriptorField("basePackage", "com.acme.badpublic")
                .descriptorField("configuration", "com.acme.badpublic.PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <include file="changes/0001.sql" relativeToChangelogFile="true"/>
                        </databaseChangeLog>
                        """)
                .entry("db/changelog/plugin/bad-public/changes/0001.sql", """
                        --liquibase formatted sql

                        --changeset acme:0001
                        CREATE TABLE public.bad_table (id int);
                        --rollback DROP TABLE public.bad_table;
                        """)
                .build();

        try (Connection admin =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS plugin_bad_public CASCADE");
                st.execute("DROP TABLE IF EXISTS public.bad_table");
            }
        }

        assertThatThrownBy(() ->
                        migrations.migrate(testDataSource(), "plugin_bad_public", "bad-public", "1.0.0", badTable))
                .isInstanceOf(PluginMigrations.PluginMigrationException.class)
                .hasMessageContaining("public");
    }

    @Test
    void migrationRefusesAForeignKeyIntoPublic() throws Exception {
        try (Connection admin =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS plugin_bad_fk CASCADE");
            }
        }

        Path badFk = new PluginJarBuilder("bad-fk")
                .descriptorField("basePackage", "com.acme.badfk")
                .descriptorField("configuration", "com.acme.badfk.PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <include file="changes/0001.sql" relativeToChangelogFile="true"/>
                        </databaseChangeLog>
                        """)
                .entry("db/changelog/plugin/bad-fk/changes/0001.sql", """
                        --liquibase formatted sql

                        --changeset acme:0001
                        CREATE TABLE ref_holder (
                            id int PRIMARY KEY,
                            cluster_id uuid REFERENCES public.cluster(id)
                        );
                        --rollback DROP TABLE ref_holder;
                        """)
                .build();

        assertThatThrownBy(() -> migrations.migrate(testDataSource(), "plugin_bad_fk", "bad-fk", "1.0.0", badFk))
                .isInstanceOf(PluginMigrations.PluginMigrationException.class)
                .hasMessageContaining("foreign key");

        try (Connection admin =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = admin.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='plugin_bad_fk' AND tablename='ref_holder'")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("the FK-into-public activation must have been rolled back")
                        .isZero();
            }
        }
    }

    @Test
    void rollbackToTagUndoesTheActivation() throws Exception {
        Path jar = notesJar("1.0.0").build();
        try (Connection admin =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS plugin_rollback_test CASCADE");
            }
        }
        PluginMigrations.MigrationResult result =
                migrations.migrate(testDataSource(), "plugin_rollback_test", "acme-notes", "1.0.0", jar);
        assertThat(result.tag()).isEqualTo("pre-1.0.0");

        migrations.rollbackToTag(testDataSource(), "plugin_rollback_test", "pre-1.0.0", jar, "acme-notes");

        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='plugin_rollback_test' AND tablename='note'")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("rollback-to-tag must have dropped the note table again")
                        .isZero();
            }
        }
    }

    /**
     * A regression test for a real defect this session found and fixed: Liquibase's {@code
     * FastCheckService} caches "changelog X against schema Y is already fully applied" keyed by
     * (URL, schema) as a JVM-wide singleton. A fresh plugin schema's very first {@code migrate()} —
     * every Instant install or update runs one, even with zero pending changesets — cached "up to
     * date" for that schema; a later {@code migrate()} against the SAME schema that genuinely had a
     * new changeset to apply was then wrongly fast-pathed as already current and silently skipped,
     * with {@code MigrationResult#applied()} misleadingly still listing it (it reflects the
     * changelog's own declared changesets, not what Liquibase actually ran). {@link
     * PluginMigrations#migrate} now clears that cache before every run.
     */
    @Test
    void aSecondMigrateOnTheSameIdAppliesANewlyAddedChangeset() throws Exception {
        String id = "acme-second-migrate";
        String schema = "plugin_second_migrate";
        try (Connection admin =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
        Path emptyJar = new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme.secondmigrate")
                .descriptorField("configuration", "com.acme.secondmigrate.PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .build();
        migrations.migrate(testDataSource(), schema, id, "1.0.0", emptyJar);

        Path jarWithTable = new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme.secondmigrate")
                .descriptorField("configuration", "com.acme.secondmigrate.PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <include file="changes/0001.sql" relativeToChangelogFile="true"/>
                        </databaseChangeLog>
                        """)
                .entry("db/changelog/plugin/" + id + "/changes/0001.sql", """
                        --liquibase formatted sql

                        --changeset acme:0001
                        CREATE TABLE second_migrate_thing (id uuid NOT NULL PRIMARY KEY);
                        --rollback DROP TABLE second_migrate_thing;
                        """)
                .build();
        migrations.migrate(testDataSource(), schema, id, "2.0.0", jarWithTable);

        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='" + schema
                            + "' AND tablename='second_migrate_thing'")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("the second migrate() call must have actually applied the newly added changeset,"
                                + " not just reported it")
                        .isEqualTo(1);
            }
        }
    }

    /** A plain pooled datasource pointed at the shared test Postgres, for the migration-only tests. */
    private javax.sql.DataSource testDataSource() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(3);
        config.setPoolName("plugin-migrations-test-pool-" + System.nanoTime());
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
}
