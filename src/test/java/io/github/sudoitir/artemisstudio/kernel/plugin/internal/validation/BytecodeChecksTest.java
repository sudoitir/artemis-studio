package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * What the bytecode scan refuses, one plugin jar per rule family. Nothing is loaded: each
 * class is compiled from source into a jar and read back as bytes, so a violation here is
 * a violation found without running plugin code.
 */
class BytecodeChecksTest {

    private static final String PKG = "package com.acme.acme_notes;\n";

    @SuppressWarnings("unchecked")
    private static List<Violation> validate(PluginJarBuilder jar) throws Exception {
        ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);
        when(noBuildInfo.getIfAvailable()).thenReturn(null);
        var studioVersion =
                new StudioVersion(noBuildInfo, new PluginProperties("2026.03.15", false, null, null, null, null, null));
        return new PluginValidator(new PluginDescriptorParser(), studioVersion)
                .validate(jar.build(), Set.of())
                .violations();
    }

    private static PluginJarBuilder plugin() {
        String id = "acme-notes";
        return new PluginJarBuilder(id)
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", id + ":read", "description", "Read notes", "scope", "cluster")))
                .descriptorField("settingKeys", List.of(id + ".enabled"))
                .descriptorField("streamTopics", List.of(id))
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name",
                                "acme_notes_search",
                                "posture",
                                "read",
                                "scope",
                                "global",
                                "permission",
                                id + ":read",
                                "description",
                                "Search notes")));
    }

    private static List<Violation> withCode(List<Violation> violations, String code) {
        return violations.stream().filter(v -> v.code().equals(code)).toList();
    }

    @Test
    void everyDeniedAnnotationIsNamedWhereItIsUsed() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.Jobs", PKG + """
                        import org.springframework.scheduling.annotation.Async;
                        import org.springframework.scheduling.annotation.Scheduled;
                        public class Jobs {
                            @Async public void a() {}
                            @Scheduled(fixedRate = 1) public void s() {}
                        }
                        """)
                .source("com.acme.acme_notes.Auto", PKG + """
                        import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
                        @EnableAutoConfiguration
                        public class Auto {}
                        """)
                .source("com.acme.acme_notes.App", PKG + """
                        import org.springframework.boot.autoconfigure.SpringBootApplication;
                        @SpringBootApplication
                        public class App {}
                        """);

        List<Violation> denied = withCode(validate(jar), "bytecode-denied-annotation");

        assertThat(denied)
                .extracting(Violation::message)
                .anyMatch(m -> m.contains("Jobs#a") && m.contains("@Async"))
                .anyMatch(m -> m.contains("Jobs#s") && m.contains("@Scheduled"))
                .anyMatch(m -> m.contains("com.acme.acme_notes.Auto") && m.contains("@EnableAutoConfiguration"))
                .anyMatch(m -> m.contains("com.acme.acme_notes.App") && m.contains("@SpringBootApplication"));
    }

    @Test
    void componentScanAndImportMayNotReachOutsideTheBasePackage() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.ScanString", PKG + """
                        import org.springframework.context.annotation.ComponentScan;
                        @ComponentScan(basePackages = {"com.acme.acme_notes.inner", "com.other"})
                        public class ScanString {}
                        """)
                .source("com.acme.acme_notes.ScanValue", PKG + """
                        import org.springframework.context.annotation.ComponentScan;
                        @ComponentScan("com.acme.acme_notes_evil")
                        public class ScanValue {}
                        """)
                .source("com.acme.acme_notes.ImportClass", PKG + """
                        import org.springframework.context.annotation.Import;
                        @Import(java.util.ArrayList.class)
                        public class ImportClass {}
                        """)
                .source("com.acme.acme_notes.ScanClasses", PKG + """
                        import org.springframework.context.annotation.ComponentScan;
                        @ComponentScan(basePackageClasses = String.class)
                        public class ScanClasses {}
                        """)
                .source("com.acme.acme_notes.ScanInside", PKG + """
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Import;
                        @ComponentScan(basePackages = "com.acme.acme_notes", excludeFilters = {})
                        @Import(ScanInside.class)
                        public class ScanInside {}
                        """);

        List<Violation> outside = withCode(validate(jar), "bytecode-scan-outside-base-package");

        assertThat(outside)
                .extracting(Violation::message)
                .anyMatch(m -> m.contains("ScanString") && m.contains("\"com.other\""))
                .anyMatch(m -> m.contains("ScanValue") && m.contains("com.acme.acme_notes_evil"))
                .anyMatch(m -> m.contains("ImportClass") && m.contains("@Import") && m.contains("java.util"))
                .anyMatch(m -> m.contains("ScanClasses") && m.contains("java.lang"))
                .noneMatch(m -> m.contains("ScanInside"))
                .noneMatch(m -> m.contains("com.acme.acme_notes.inner\""));
    }

    @Test
    void configurationPropertiesMustLiveUnderThePluginsOwnPrefix() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.Exact", PKG + """
                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        @ConfigurationProperties("artemis-studio.plugins.acme-notes")
                        public class Exact {}
                        """)
                .source("com.acme.acme_notes.Nested", PKG + """
                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        @ConfigurationProperties(prefix = "artemis-studio.plugins.acme-notes.feature")
                        public class Nested {}
                        """)
                .source("com.acme.acme_notes.Sibling", PKG + """
                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        @ConfigurationProperties("artemis-studio.plugins.acme-notes-other")
                        public class Sibling {}
                        """)
                .source("com.acme.acme_notes.Bare", PKG + """
                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        @ConfigurationProperties
                        public class Bare {}
                        """);

        List<Violation> prefix = withCode(validate(jar), "bytecode-configuration-properties-prefix");

        assertThat(prefix)
                .extracting(Violation::message)
                .hasSize(2)
                .anyMatch(m -> m.contains("Sibling") && m.contains("acme-notes-other"))
                .anyMatch(m -> m.contains("Bare") && m.contains("\"null\""));
    }

    @Test
    void mappedPathsAreCombinedWithTheClassPrefixAndCheckedAgainstTheGateway() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.Gateway", PKG + """
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping(path = {"/api/v1/p/acme-notes/", "/api/v1/clusters/{clusterId}/p/acme-notes"})
                        public class Gateway {
                            @GetMapping("list") public String list() { return ""; }
                            @PutMapping("/put") public String put() { return ""; }
                            @DeleteMapping public String del() { return ""; }
                            @PatchMapping(value = "/patch") public String patch() { return ""; }
                            @RequestMapping(path = "/any") public String any() { return ""; }
                        }
                        """)
                .source("com.acme.acme_notes.Rogue", PKG + """
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class Rogue {
                            @PostMapping("api/v1/p/acme-notes-evil") public String evil() { return ""; }
                            @RequestMapping(value = {"/ok", "/api/v1/p/acme-notes/fine"}) public String mixed() { return ""; }
                            @GetMapping(name = "unnamed") public String root() { return ""; }
                        }
                        """);

        List<Violation> mapping = withCode(validate(jar), "bytecode-mapping-path");

        assertThat(mapping)
                .extracting(Violation::message)
                .noneMatch(m -> m.contains("Gateway"))
                .anyMatch(m -> m.contains("Rogue#evil") && m.contains("\"/api/v1/p/acme-notes-evil\""))
                .anyMatch(m -> m.contains("Rogue#mixed") && m.contains("\"/ok\""))
                .noneMatch(m -> m.contains("Rogue#mixed") && m.contains("fine"))
                .anyMatch(m -> m.contains("Rogue#root") && m.contains("\"/\""));
    }

    @Test
    void everyDeniedCallSiteIsNamed() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.Calls", PKG + """
                        @SuppressWarnings({"removal", "deprecation"})
                        public class Calls {
                            public void halt() { Runtime.getRuntime().halt(1); }
                            public void exec() throws Exception { Runtime.getRuntime().exec("ls"); }
                            public void proc() { new ProcessBuilder("ls"); }
                            public void stop(Thread t) { t.stop(); }
                            public void loader(Thread t) { t.setContextClassLoader(null); }
                            public void session(jakarta.servlet.http.HttpSession s) { s.setAttribute("k", "v"); }
                        }
                        """).source("com.acme.acme_notes.Fine", PKG + """
                        public class Fine {
                            public long now() { return System.currentTimeMillis(); }
                            public int cpus() { return Runtime.getRuntime().availableProcessors(); }
                            public Object get(jakarta.servlet.http.HttpSession s) { return s.getAttribute("k"); }
                            public String name(Thread t) { return t.getName(); }
                        }
                        """);

        List<Violation> calls = withCode(validate(jar), "bytecode-denied-call");

        assertThat(calls)
                .extracting(Violation::message)
                .hasSize(6)
                .anyMatch(m -> m.contains("Runtime.halt"))
                .anyMatch(m -> m.contains("Runtime.exec"))
                .anyMatch(m -> m.contains("new ProcessBuilder(...)"))
                .anyMatch(m -> m.contains("Thread.stop"))
                .anyMatch(m -> m.contains("Thread.setContextClassLoader"))
                .anyMatch(m -> m.contains("HttpSession.setAttribute"))
                .noneMatch(m -> m.contains("Fine"));
    }

    @Test
    void aCleanClassWithoutAnnotationsRaisesNothing() throws Exception {
        var jar = plugin().source("com.acme.acme_notes.Plain", PKG + """
                        public class Plain { public int one() { return 1; } }
                        """);

        assertThat(validate(jar)).extracting(Violation::code).noneMatch(c -> c.startsWith("bytecode-"));
    }
}
