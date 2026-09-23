package io.github.sudoitir.artemisstudio.kernel.core.internal;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * The console is a client-routed SPA: every screen is a real URL the user can
 * bookmark, refresh, or open from a link. Without this, anything but {@code /}
 * hits the static handler, finds no file, and returns Spring's 404 page.
 *
 * <p>Unknown {@code api/}, {@code actuator/} and {@code mcp} paths keep 404-ing — the client
 * parses those as problem details, so handing them an HTML shell with a 200
 * would turn a missing endpoint into an unreadable error.
 */
@Configuration
public class SpaRoutingConfig implements WebMvcConfigurer {

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    /**
     * The MCP transport registers a functional {@code RouterFunction}, which
     * {@code RouterFunctionMapping} (order -1) resolves before this resource
     * handler — so in practice this branch is belt and braces. It matters for the
     * probe a human or client makes by hand: a bare {@code GET /mcp} does not
     * match the transport's POST route, and without this it would fall through to
     * {@code index.html} with a {@code 200} — exactly the unreadable-error failure
     * this class already avoids for {@code api/}.
     */
    private static boolean isMcp(String path) {
        return path.equals("mcp") || path.startsWith("mcp/");
    }

    /**
     * {@code /plugin-ui/<id>/<sha8>/**} is served by {@code PluginAssetController} (task 6.10);
     * a missing entry there must 404, the same as a missing {@code api/} endpoint, rather than
     * being handed the SPA shell.
     */
    private static boolean isPluginUi(String path) {
        return path.equals("plugin-ui") || path.startsWith("plugin-ui/");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource asset = location.createRelative(path);
                        if (asset.exists() && asset.isReadable()) {
                            return asset;
                        }
                        if (path.startsWith("api/")
                                || path.startsWith("actuator/")
                                || isMcp(path)
                                || isPluginUi(path)) {
                            return null;
                        }
                        return INDEX.exists() ? INDEX : null;
                    }
                });
    }
}
