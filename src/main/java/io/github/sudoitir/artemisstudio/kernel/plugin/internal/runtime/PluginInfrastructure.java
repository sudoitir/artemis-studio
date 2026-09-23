package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registered into every plugin's own {@code GenericWebApplicationContext} alongside the plugin's
 * own {@code configuration()} class (design.md §3, spike criterion 2): transactions, method
 * security and validation, and web MVC. A plugin controller gets a CGLIB proxy for
 * {@code @PreAuthorize}/{@code @Transactional} to apply the same way the main application's do,
 * and {@code @perm}-denied calls answer {@code 403} with a problem body instead of an unhandled
 * exception reaching the gateway.
 *
 * <p>The two {@code BeanPostProcessor} beans below are {@code static} on purpose: a
 * {@code @Configuration} class that hosts a non-static {@code BeanPostProcessor} {@code @Bean} is
 * itself instantiated early, during {@code registerBeanPostProcessors()}, <em>before</em>
 * {@code AutowiredAnnotationBeanPostProcessor} exists to run — so a non-static factory method's
 * enclosing instance is only ever partially wired. Static methods sidestep the enclosing instance
 * (and the "not eligible for auto-proxying" warning) entirely, which is also why the JSON message
 * converter (needing the autowired {@code jsonMapper}, not knowable statically) lives in the
 * separate {@link PluginMessageConverterConfig} rather than here.
 *
 * <p><b>Must be a top-level class</b>, never nested in a test class: a {@code @Configuration}
 * nested in a {@code @SpringBootTest} is auto-detected by Boot's test bootstrap as the test's own
 * configuration, which silently replaces the real application context search entirely (the spike
 * hit this).
 *
 * <p><b>Deliberately not {@code @Configuration}</b>, and neither is
 * {@link PluginMessageConverterConfig}: the application scans {@code kernel}, and a scanned
 * {@code @EnableWebMvc} would switch off Boot's own MVC auto-configuration for every core endpoint
 * (and the converter would re-wire the host's message converters). Each {@code @Enable…} is an
 * {@code @Import}, so a plugin context that registers this class explicitly still processes it as
 * configuration; component scanning never picks it up. {@code PluginInfrastructureIsolationTest}
 * holds this.
 */
@EnableTransactionManagement(proxyTargetClass = true)
@EnableMethodSecurity
@EnableWebMvc
public class PluginInfrastructure {

    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }

    /** Makes {@code @PersistenceContext} work on a plugin's own {@code @Service}/{@code @Repository} beans. */
    @Bean
    static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
        return new PersistenceAnnotationBeanPostProcessor();
    }

    @Bean
    PluginAccessDeniedAdvice pluginAccessDeniedAdvice() {
        return new PluginAccessDeniedAdvice();
    }
}

/**
 * The JSON message converter, rebuilt from the curated {@code jsonMapper} singleton
 * ({@code jsonMapper.rebuild().build()}) rather than reused directly, so a plugin's own
 * {@code @JsonView}/mixin registrations on its rebuilt copy can never leak back into the host's.
 * Kept separate from {@link PluginInfrastructure} — see its javadoc — so ordinary constructor
 * autowiring applies here with no early-instantiation ordering hazard.
 */
class PluginMessageConverterConfig implements WebMvcConfigurer {

    private final JsonMapper jsonMapper;

    PluginMessageConverterConfig(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // extendMessageConverters, not configureMessageConverters: the latter REPLACES the whole
        // default list rather than adding to it, which would drop StringHttpMessageConverter and
        // have every plain-String response come back JSON-quoted instead of as text/plain.
        // Appended, not prepended, so it never pre-empts an earlier converter (String, byte[],
        // resource) for a type those already handle; it only ever adds a JSON converter built
        // from the plugin's own rebuilt copy of the curated jsonMapper for whatever needs one.
        converters.add(new JacksonJsonHttpMessageConverter(jsonMapper.rebuild().build()));
    }
}

/**
 * A plugin's {@code @PreAuthorize} refusal as a problem body. Registered by
 * {@link PluginInfrastructure}; the condition keeps the application's own component scan (which
 * covers {@code kernel}) from adding it to the main context, where it would rewrite every core
 * access denial.
 */
@RestControllerAdvice
@Conditional(PluginContextOnly.class)
class PluginAccessDeniedAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(AccessDeniedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "plugin-access-denied", "Access denied", e.getMessage());
    }
}

/** True only in a context {@link PluginRuntimeFactory} built, which registers the marker singleton first. */
final class PluginContextOnly implements Condition {

    static final String MARKER = "artemisStudioPluginContext";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var beanFactory = context.getBeanFactory();
        return beanFactory != null && beanFactory.containsSingleton(MARKER);
    }
}
