package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

/** Wires {@link PluginGateway} in ahead of the main application's own {@code @RequestMapping}s. */
@Configuration
class PluginGatewayConfig {

    @Bean
    SimpleUrlHandlerMapping pluginGatewayMapping(PluginGateway gateway) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        mapping.setUrlMap(Map.of(
                "/api/v1/p/**", gateway,
                "/api/v1/clusters/*/p/**", gateway));
        return mapping;
    }
}
