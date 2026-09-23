package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one curated {@link GenericApplicationContext} every plugin's own context is parented on
 * (design.md, task 6.1). Built once, from every main-context bean whose concrete class carries
 * {@link PluginApi}, registered under its main-context bean name — {@code perm} stays {@code perm}
 * — plus {@link Clock} and {@link JsonMapper}, registered explicitly because neither is a Studio
 * class we can annotate. Nothing here imports a platform or feature type: it only ever asks the
 * main context for beans by annotation or by a JDK/library type, which is what keeps this class
 * inside {@code kernel.plugin}'s own module boundary (its {@code allowedDependencies} is
 * {@code kernel.core} alone) while still curating beans that live in platform and feature modules.
 */
@Component
public class PluginApiContext {

    private final GenericApplicationContext context;

    public PluginApiContext(ApplicationContext mainContext) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        Map<String, Object> exported = mainContext.getBeansWithAnnotation(PluginApi.class);
        exported.forEach((name, bean) -> ctx.getBeanFactory().registerSingleton(name, bean));
        ctx.getBeanFactory().registerSingleton("clock", mainContext.getBean(Clock.class));
        ctx.getBeanFactory().registerSingleton("jsonMapper", mainContext.getBean(JsonMapper.class));
        ctx.refresh();
        this.context = ctx;
    }

    /** The parent every plugin's own {@code GenericWebApplicationContext} is built with. */
    public GenericApplicationContext context() {
        return context;
    }
}
