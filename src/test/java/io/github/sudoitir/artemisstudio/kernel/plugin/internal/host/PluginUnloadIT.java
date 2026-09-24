package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A plugin that did real work unloads completely (design.md §2, ADR-0104): its API answered JSON
 * built from its own types behind a {@code @PreAuthorize} reading a parameter, its assistant tool
 * was listed and called with typed input and output, and it contributed a job and a setting.
 * Every JVM-wide cache that was found holding such a plugin — Spring Security's annotation
 * scanners, Spring AI's JSON mapper and MCP schema caches, Spring's {@code @Bean} metadata — is
 * covered here; without the eviction, this test fails with a heap dump in {@code target/}.
 */
class PluginUnloadIT extends PostgresIntegrationTest {

    private static final String ID = "acme-unload";
    private static final String PKG = "com.acme.unload";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginHost host;

    @Autowired
    PluginStore store;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    @AfterEach
    void cleanUp() {
        registry.remove(ID);
        jdbc.update("DELETE FROM plugin_install WHERE id = ?", ID);
    }

    private PluginJarBuilder plugin() {
        return new PluginJarBuilder(ID)
                .descriptorField("basePackage", PKG)
                .descriptorField("configuration", PKG + ".Config")
                .descriptorField("permissions", List.of(Map.of("action", ID + ":read")))
                .descriptorField("settingKeys", List.of(ID + ".limit"))
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of("name", "acme_unload_list", "posture", "read", "description", "Lists.")))
                .source(PKG + ".Config", """
                        package com.acme.unload;
                        import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
                        import java.time.Duration;
                        import org.springframework.context.annotation.*;
                        @Configuration
                        @ComponentScan(basePackageClasses = Config.class)
                        public class Config {
                            @Bean
                            ScheduledJob tick() {
                                return ScheduledJob.fixedDelay("acme-unload-tick", "acme-unload", () -> Duration.ofHours(1), () -> {});
                            }
                        }
                        """)
                .source(PKG + ".Item", """
                        package com.acme.unload;
                        public record Item(String name, java.util.List<String> tags) {}
                        """)
                .source(PKG + ".Api", """
                        package com.acme.unload;
                        import java.util.List;
                        import java.util.UUID;
                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1/clusters/{clusterId}/p/acme-unload")
                        public class Api {
                            @GetMapping("/items/{name}")
                            @PreAuthorize("@perm.can(#clusterId, 'acme-unload:read') && #name != 'nope'")
                            public List<Item> items(@PathVariable UUID clusterId, @PathVariable String name) {
                                return List.of(new Item(name, List.of("a")));
                            }
                        }
                        """)
                .source(PKG + ".Tools", """
                        package com.acme.unload;
                        import java.util.List;
                        import org.springframework.ai.mcp.annotation.McpTool;
                        import org.springframework.ai.mcp.annotation.McpToolParam;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Tools {
                            @McpTool(name = "acme_unload_list", description = "Lists.")
                            public List<Item> list(@McpToolParam(required = true, description = "A name") String name) {
                                return List.of(new Item(name, List.of("b")));
                            }
                        }
                        """)
                .source(PKG + ".Settings", """
                        package com.acme.unload;
                        import io.github.sudoitir.artemisstudio.kernel.settings.*;
                        import java.util.List;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Settings implements SettingsContribution {
                            public String featureId() { return "acme-unload"; }
                            public List<SettingDef> settings() {
                                return List.of(new SettingDef("acme-unload.limit", "plugins", "Limit", "hint",
                                        SettingDef.Kind.INT, () -> "5", null));
                            }
                        }
                        """);
    }

    @Test
    void aPluginThatDidRealWorkIsCollectedAfterItIsUninstalled() throws Exception {
        WeakReference<ClassLoader> loader = installExerciseAndUninstall();
        for (int i = 0; i < 25 && loader.get() != null; i++) {
            System.gc();
            Thread.sleep(200);
        }
        if (loader.get() != null) {
            java.lang.management.ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                            new javax.management.ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                            "dumpHeap",
                            new Object[] {"target/plugin-unload.hprof", true},
                            new String[] {"java.lang.String", "boolean"});
        }
        assertThat(loader.get())
                .as("the unloaded plugin's classes are still reachable; heap dump in target/plugin-unload.hprof")
                .isNull();
    }

    /** Its own frame, so no local variable of it is still on the stack while the test waits for collection. */
    private WeakReference<ClassLoader> installExerciseAndUninstall() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        var key = McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.GLOBAL, null, Set.of("*"));
        String sha = store.put(Files.readAllBytes(plugin().build()));
        host.activate(sha, "test");
        for (int i = 0;
                i < 300
                        && host.status(ID)
                                .map(s -> s.status() != PluginInstallStatus.ACTIVE)
                                .orElse(true);
                i++) {
            Thread.sleep(100);
        }
        var active = (PluginRuntimeRegistry.Active) registry.get(ID).orElseThrow();
        WeakReference<ClassLoader> loader = new WeakReference<>(active.runtime().classLoader());

        String cluster = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/clusters/{c}/p/acme-unload/items/n{i}", cluster, i)
                            .header("Authorization", key.bearer()))
                    .andExpect(status().isOk());
        }
        McpFixture.rpc(mvc, key, "tools/list", null);
        assertThat(McpFixture.callTool(mvc, key, "acme_unload_list", Map.of("name", "x"))
                        .toString())
                .contains("\"isError\":false")
                .contains("tags");

        host.uninstall(ID, false, "test");
        return loader;
    }
}
