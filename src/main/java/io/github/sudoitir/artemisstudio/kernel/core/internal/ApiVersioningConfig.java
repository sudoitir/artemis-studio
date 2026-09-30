package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Unversioned;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The REST API's version is the second path segment ({@code /api/v1/...}), resolved by Spring's API
 * versioning (ADR-0147). Controllers map only what follows it; the prefix is added here for every
 * Studio {@code @RestController} that is not {@link Unversioned}. Only {@code 1} is supported, so
 * {@code /api/v2/...} is a 400 {@code invalid-api-version} until a v2 exists.
 */
@Configuration
class ApiVersioningConfig implements WebMvcConfigurer {

    private static final String BASE_PACKAGE = "io.github.sudoitir.artemisstudio";

    private final ObjectProvider<ApiDeprecation> deprecations;

    ApiVersioningConfig(ObjectProvider<ApiDeprecation> deprecations) {
        this.deprecations = deprecations;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                "/api/{version}",
                type -> type.getPackageName().startsWith(BASE_PACKAGE)
                        && AnnotatedElementUtils.hasAnnotation(type, RestController.class)
                        && !AnnotatedElementUtils.hasAnnotation(type, Unversioned.class));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();
        deprecations.orderedStream().forEach(d -> {
            var spec = handler.configureVersion(d.version())
                    .setDeprecationDate(d.deprecated())
                    .setSunsetDate(d.sunset())
                    .setDeprecationLink(d.link())
                    .setSunsetLink(d.link());
            if (d.path() != null) {
                var pattern = PathPatternParser.defaultInstance.parse(d.path());
                spec.setRequestPredicate(
                        request -> request.getMethod().equals(d.method().name())
                                && pattern.matches(PathContainer.parsePath(request.getRequestURI())));
            }
        });
        configurer
                // Segment 0 is "api". Everything outside /api resolves no version, and needs none.
                .usePathSegment(1, path -> path.value().startsWith("/api/"))
                .setVersionRequired(false)
                .detectSupportedVersions(false)
                .addSupportedVersions("1")
                .setDeprecationHandler(handler);
    }
}
