package io.github.sudoitir.artemisstudio.config;

import io.github.sudoitir.artemisstudio.Branding;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the OpenAPI document's {@code info} and drops the generated server list so
 * {@code web/openapi.json} (ADR-0019) is a stable snapshot — it must not change
 * just because the test ran on a different host or port.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI artemisStudioOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(Branding.PRODUCT_NAME + " API")
                        .description(Branding.TAGLINE)
                        .version("v1"))
                .servers(List.of());
    }
}
