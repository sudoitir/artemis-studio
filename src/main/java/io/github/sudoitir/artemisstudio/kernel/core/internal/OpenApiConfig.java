package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;

/**
 * Pins the OpenAPI document's {@code info} (its version is the running Studio version) and drops the generated server list so
 * {@code web/openapi.json} (ADR-0019) is a stable snapshot — it must not change
 * just because the test ran on a different host or port.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI artemisStudioOpenApi(BuildProperties build) {
        return new OpenAPI()
                .info(new Info()
                        .title(Branding.PRODUCT_NAME + " API")
                        .description(Branding.TAGLINE)
                        .version(build.getVersion()))
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

    /**
     * Every operation can fail, and every failure is a problem (api-contract spec): {@code 4XX} and
     * {@code 5XX} carry {@code application/problem+json}, and 429 names the headers a client backs off by.
     * An operation that documents one of these itself keeps its own.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            var resolved =
                    ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(ProblemDetail.class));
            resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
            Content problem = new Content()
                    .addMediaType(
                            org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
            ApiResponse tooMany = new ApiResponse()
                    .description("Too many requests. Wait for Retry-After seconds.")
                    .content(problem)
                    .addHeaderObject("Retry-After", header("Seconds to wait before retrying."))
                    .addHeaderObject("RateLimit-Limit", header("Requests allowed in the window (API tokens)."))
                    .addHeaderObject("RateLimit-Remaining", header("Requests left in the window (API tokens)."))
                    .addHeaderObject("RateLimit-Reset", header("Seconds until the window resets (API tokens)."));
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(op -> {
                        op.getResponses()
                                .putIfAbsent(
                                        "4XX",
                                        new ApiResponse()
                                                .description("Client error")
                                                .content(problem));
                        op.getResponses()
                                .putIfAbsent(
                                        "5XX",
                                        new ApiResponse()
                                                .description("Server error")
                                                .content(problem));
                        op.getResponses().putIfAbsent("429", tooMany);
                    }));
        };
    }

    /**
     * Controllers map paths without {@code /api/v1}; Spring adds {@code /api/{version}} (see
     * {@link ApiVersioningConfig}). The document keeps concrete {@code /api/v1/...} paths and no
     * {@code version} parameter, and marks the operations {@link ApiDeprecations} declares deprecated.
     */
    @Bean
    OpenApiCustomizer versionedPaths(ObjectProvider<ApiDeprecation> deprecations) {
        return openApi -> {
            Paths concrete = new Paths();
            openApi.getPaths().forEach((path, item) -> {
                item.readOperations().forEach(op -> {
                    if (op.getParameters() != null) {
                        op.getParameters().removeIf(p -> "version".equals(p.getName()) && "path".equals(p.getIn()));
                    }
                });
                concrete.addPathItem(path.replace("/api/{version}/", "/api/v1/"), item);
            });
            openApi.setPaths(concrete);
            List<ApiDeprecation> declared = deprecations.orderedStream().toList();
            concrete.forEach((path, item) -> item.readOperationsMap()
                    .forEach((method, op) -> declared.stream()
                            .filter(d -> path.startsWith("/api/v" + d.version() + "/"))
                            .filter(d -> d.path() == null
                                    || d.path().equals(path)
                                            && d.method().name().equals(method.name()))
                            .findFirst()
                            .ifPresent(d -> {
                                op.setDeprecated(true);
                                op.setDescription((op.getDescription() == null ? "" : op.getDescription() + "\n\n")
                                        + "Deprecated since " + d.deprecated().toLocalDate() + "; sunset "
                                        + d.sunset().toLocalDate() + ".");
                            })));
        };
    }

    private static Header header(String description) {
        return new Header().description(description).schema(new IntegerSchema());
    }
}
