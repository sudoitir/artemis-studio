package io.github.sudoitir.artemisstudio.config;

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
 * <p>Unknown {@code api/} and {@code actuator/} paths keep 404-ing — the client
 * parses those as problem details, so handing them an HTML shell with a 200
 * would turn a missing endpoint into an unreadable error.
 */
@Configuration
public class SpaRoutingConfig implements WebMvcConfigurer {

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

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
                        if (path.startsWith("api/") || path.startsWith("actuator/")) {
                            return null;
                        }
                        return INDEX.exists() ? INDEX : null;
                    }
                });
    }
}
