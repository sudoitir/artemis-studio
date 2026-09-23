package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatus;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.stream.StreamTopicRegistry;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The copy-on-write registries and their bridges (design.md, task 6.4/6.5/6.6): attach registers a
 * plugin's settings, stream topic, scheduled job and MCP tool with the real, running host
 * services, and detach removes every one of them — the registries are left exactly as they were,
 * built-in behaviour untouched.
 */
class PluginBridgesIT extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    SettingsService settingsService;

    @Autowired
    StreamTopicRegistry streamTopics;

    @Autowired
    JobStatuses jobStatuses;

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

    private PluginRuntime activeRuntime;

    @AfterEach
    void cleanUp() {
        if (activeRuntime != null) {
            activeRuntime.close();
            registry.remove(activeRuntime.id());
        }
        SecurityContextHolder.clearContext();
    }

    private PluginDescriptor descriptorOf(Path jar) throws Exception {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            byte[] json = jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                    .readAllBytes();
            return descriptorParser.parse(json);
        }
    }

    private void administrate() {
        StudioPrincipal principal = new StudioPrincipal(
                UUID.randomUUID(),
                "bridges-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("*"))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    /** A fixture plugin contributing one setting, one stream topic + replay, one job and one MCP tool. */
    private PluginJarBuilder bridgesJar(String id) {
        return new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme.bridges")
                .descriptorField("configuration", "com.acme.bridges.PluginConfig")
                .descriptorField("settingKeys", List.of(id + ".limit"))
                .descriptorField("streamTopics", List.of(id))
                .descriptorField(
                        "mcpTools",
                        List.of(java.util.Map.of(
                                "name", id.replace('-', '_') + "_ping",
                                "posture", "read",
                                "description", "Replies pong.")))
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .source("com.acme.bridges.PluginConfig", """
                        package com.acme.bridges;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        @ComponentScan(basePackages = "com.acme.bridges")
                        public class PluginConfig {}
                        """)
                .source("com.acme.bridges.BridgesSettings", """
                        package com.acme.bridges;
                        import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
                        import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
                        import java.util.List;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class BridgesSettings implements SettingsContribution {
                            public String featureId() { return "%s"; }
                            public List<SettingDef> settings() {
                                return List.of(new SettingDef(
                                        "%s.limit", "plugins", "Limit", "hint",
                                        SettingDef.Kind.INT, () -> "5", null));
                            }
                        }
                        """.formatted(id, id))
                .source("com.acme.bridges.BridgesReplay", """
                        package com.acme.bridges;
                        import io.github.sudoitir.artemisstudio.kernel.stream.EventReplay;
                        import java.util.List;
                        import java.util.UUID;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class BridgesReplay implements EventReplay {
                            public String topic() { return "%s"; }
                            public List<Replayed> since(UUID clusterId, long lastEventId, int cap) {
                                return List.of();
                            }
                        }
                        """.formatted(id))
                .source("com.acme.bridges.BridgesJobs", """
                        package com.acme.bridges;
                        import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
                        import java.time.Duration;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        public class BridgesJobs {
                            @Bean
                            public ScheduledJob bridgesJob() {
                                return ScheduledJob.fixedDelay(
                                        "%s-job", "%s", () -> Duration.ofMinutes(5), () -> {});
                            }
                        }
                        """.formatted(id, id))
                .source("com.acme.bridges.BridgesMcpTools", """
                        package com.acme.bridges;
                        import org.springframework.ai.mcp.annotation.McpTool;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class BridgesMcpTools {
                            @McpTool(name = "%s_ping", description = "Replies pong.")
                            public String ping() { return "pong"; }
                        }
                        """.formatted(id.replace('-', '_')));
    }

    @Test
    void attachRegistersEveryBridgeAndDetachRemovesThemAll() throws Exception {
        String id = "acme-bridges-" + Math.abs(new SecureRandom().nextInt());
        Path jar = bridgesJar(id).build();
        PluginDescriptor descriptor = descriptorOf(jar);

        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set(id, new PluginRuntimeRegistry.Active(activeRuntime));

        // Settings
        administrate();
        assertThat(settingsService.effective()).containsKey(id + ".limit");
        assertThat(settingsService.value(id + ".limit")).isEqualTo("5");

        // Stream topics
        assertThat(streamTopics.known()).contains(id);

        // Jobs
        assertThat(jobStatuses.all()).extracting(JobStatus::id).contains(id + "-job");

        // MCP: listed, callable and documented through the real McpStatelessSyncServer
        var key = McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.GLOBAL, null, Set.of("*"));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        String toolName = id.replace('-', '_') + "_ping";

        JsonNode listed = McpFixture.rpc(mvc, key, "tools/list", null);
        assertThat(listed.path("result").path("tools").toString()).contains(toolName);

        JsonNode help = McpFixture.callTool(mvc, key, "studio_help", java.util.Map.of());
        assertThat(help.toString()).contains(toolName);

        JsonNode called = McpFixture.callTool(mvc, key, toolName, java.util.Map.of());
        assertThat(called.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("pong");

        // Detach: close the runtime and assert every registry is back to how it was.
        activeRuntime.close();
        registry.remove(id);
        activeRuntime = null;

        // MockMvc's security filter chain overwrites SecurityContextHolder per request.
        administrate();
        assertThat(settingsService.effective()).doesNotContainKey(id + ".limit");
        assertThat(streamTopics.known()).doesNotContain(id);
        assertThat(jobStatuses.all()).extracting(JobStatus::id).doesNotContain(id + "-job");

        JsonNode listedAfter = McpFixture.rpc(mvc, key, "tools/list", null);
        assertThat(listedAfter.path("result").path("tools").toString()).doesNotContain(toolName);
    }

    @Test
    void anUpdatingPluginsToolReturnsTheRetryErrorResult() throws Exception {
        String id = "acme-bridges-" + Math.abs(new SecureRandom().nextInt());
        Path jar = bridgesJar(id).build();
        PluginDescriptor descriptor = descriptorOf(jar);

        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set(id, new PluginRuntimeRegistry.Active(activeRuntime));

        var key = McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.GLOBAL, null, Set.of("*"));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        registry.set(id, new PluginRuntimeRegistry.Updating(7));
        JsonNode called = McpFixture.callTool(mvc, key, id.replace('-', '_') + "_ping", java.util.Map.of());
        assertThat(called.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(called.path("result").path("content").get(0).path("text").asString())
                .contains("is updating, retry in 7s");
    }

    @Test
    void aSettingsApplyThatThrowsFailsOnlyThatPluginsActivation() throws Exception {
        String id = "acme-bridges-" + Math.abs(new SecureRandom().nextInt());
        PluginJarBuilder builder = bridgesJar(id);
        // Override the settings contribution so its apply lambda throws on the first push.
        builder.source("com.acme.bridges.BridgesSettings", """
                package com.acme.bridges;
                import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
                import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
                import java.util.List;
                import org.springframework.stereotype.Component;
                @Component
                public class BridgesSettings implements SettingsContribution {
                    public String featureId() { return "%s"; }
                    public List<SettingDef> settings() {
                        return List.of(new SettingDef(
                                "%s.limit", "plugins", "Limit", "hint",
                                SettingDef.Kind.INT, () -> "5",
                                settings -> { throw new IllegalStateException("boom"); }));
                    }
                }
                """.formatted(id, id));
        Path jar = builder.build();
        PluginDescriptor descriptor = descriptorOf(jar);

        assertThatThrownBy(() -> runtimeFactory.activate(descriptor, jar, webContext.getServletContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        // Rolled back: the failed plugin's setting, topic and job must not be left registered.
        administrate();
        assertThat(settingsService.effective()).doesNotContainKey(id + ".limit");
        assertThat(streamTopics.known()).doesNotContain(id);
        assertThat(jobStatuses.all()).extracting(JobStatus::id).doesNotContain(id + "-job");
    }
}
