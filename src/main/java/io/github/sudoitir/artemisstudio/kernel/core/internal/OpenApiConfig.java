package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.List;
import org.springdoc.core.customizers.PropertyCustomizer;
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

    /**
     * A primitive property is required in the contract (ADR-0096): Jackson 3 rejects a request that
     * omits one ({@code FAIL_ON_NULL_FOR_PRIMITIVES}), and a response always carries one.
     */
    @Bean
    PropertyCustomizer primitivesAreRequired() {
        return (property, type) -> {
            if (type.getParent() != null
                    && type.getPropertyName() != null
                    && Json.mapper().constructType(type.getType()).isPrimitive()) {
                List<String> required = type.getParent().getRequired();
                if (required == null || !required.contains(type.getPropertyName())) {
                    type.getParent().addRequiredItem(type.getPropertyName());
                }
            }
            return property;
        };
    }
}
