package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /plugin-ui/<id>/<sha8>/**} (task 6.10, design.md §7): served by exact jar entry from
 * the active runtime's own materialized jar, gated like {@code /api/**}, never falling back to the
 * SPA shell.
 */
class PluginAssetControllerIT extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

    private PluginRuntime activeRuntime;

    @AfterEach
    void cleanUp() {
        if (activeRuntime != null) {
            activeRuntime.close();
            registry.remove(activeRuntime.id());
        }
    }

    private PluginDescriptor descriptorOf(Path jar) throws Exception {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            byte[] json = jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                    .readAllBytes();
            return descriptorParser.parse(json);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    private static UsernamePasswordAuthenticationToken admin() {
        StudioPrincipal principal = new StudioPrincipal(
                UUID.randomUUID(),
                "asset-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("*"))),
                false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Test
    void servedWithCorrectHeadersAndWrongOrMissingRequestsAllFourOhFour() throws Exception {
        String id = "acme-assets-" + Math.abs(new SecureRandom().nextInt());
        Path jar = new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme.assets")
                .descriptorField("configuration", "com.acme.assets.PluginConfig")
                .descriptorField("ui", true)
                .entry("META-INF/artemis-studio/ui/remoteEntry.js", "export default {};")
                .entry("META-INF/artemis-studio/ui/style.css", "body{color:red}")
                .entry("META-INF/artemis-studio/icon.svg", "<svg xmlns='http://www.w3.org/2000/svg'></svg>")
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
        PluginDescriptor descriptor = descriptorOf(jar);
        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set(id, new PluginRuntimeRegistry.Active(activeRuntime));
        String sha8 = activeRuntime.sha256().substring(0, 8);

        MockMvc mvc = mvc();

        // Served with correct headers.
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/remoteEntry.js", id, sha8)
                        .with(authentication(admin())))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        MockMvcResultMatchers.header().string("Cache-Control", "private, max-age=31536000, immutable"))
                .andExpect(MockMvcResultMatchers.content().string("export default {};"));

        // icon.svg: served from META-INF/artemis-studio/icon.svg (not ui/icon.svg), CSP: sandbox.
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/icon.svg", id, sha8)
                        .with(authentication(admin())))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(MockMvcResultMatchers.header().string("Content-Security-Policy", "sandbox"))
                .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("<svg")));

        // Wrong sha8: 404, not the file.
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/remoteEntry.js", id, "deadbeef")
                        .with(authentication(admin())))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.content().contentType("application/problem+json"));

        // Missing file: 404 problem, never the SPA's index.html.
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/does-not-exist.js", id, sha8)
                        .with(authentication(admin())))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.content().contentType("application/problem+json"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .doesNotContain("<html"));

        // Traversal attempts: 404 (dot-segments are collapsed by the servlet container before this
        // controller ever sees them, so this asserts they never resolve to a real file either).
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/../../etc/passwd", id, sha8)
                        .with(authentication(admin())))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isIn(400, 404));
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/%2e%2e/%2e%2e/etc/passwd", id, sha8)
                        .with(authentication(admin())))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isIn(400, 404));
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/..%5c..%5cwindows", id, sha8)
                        .with(authentication(admin())))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isIn(400, 404));

        // Unauthenticated: 401.
        mvc.perform(MockMvcRequestBuilders.get("/plugin-ui/{id}/{sha8}/remoteEntry.js", id, sha8))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }
}
