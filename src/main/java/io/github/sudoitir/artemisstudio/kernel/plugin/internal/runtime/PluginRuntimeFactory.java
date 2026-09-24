package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Builds a {@link PluginRuntime} (design.md §3, task 6.2): a {@link URLClassLoader} parented on
 * the host's own loader, a per-plugin {@link HikariDataSource} + JPA stack scoped to the plugin's
 * own schema (created and migrated by {@link PluginMigrations} first), a child
 * {@link GenericWebApplicationContext} parented on {@link PluginApiContext}, and the plugin's own
 * {@link DispatcherServlet}. Calls every {@link PluginBridge} bean's {@code attach} once the
 * context has refreshed, before the runtime is handed back to be swapped into service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginRuntimeFactory {

    private final PluginApiContext apiContext;
    private final ApplicationContext mainContext;
    private final PluginMigrations migrations;
    private final DataSourceProperties dataSourceProperties;

    /** Builds, migrates and refreshes one plugin version's runtime, ready to be forwarded to. */
    public PluginRuntime activate(PluginDescriptor descriptor, Path jarPath, ServletContext servletContext)
            throws Exception {
        return activate(descriptor, jarPath, servletContext, step -> {});
    }

    /**
     * As {@link #activate(PluginDescriptor, Path, ServletContext)}, reporting {@code migrating}
     * before the migration and {@code starting} before the context refresh, so a caller's progress
     * names the step that is actually running.
     */
    public PluginRuntime activate(
            PluginDescriptor descriptor, Path jarPath, ServletContext servletContext, Consumer<String> onStep)
            throws Exception {
        String pluginId = descriptor.id();
        String schema = "plugin_" + pluginId.replace('-', '_');
        URLClassLoader loader = new URLClassLoader(
                "plugin-" + pluginId + "-" + descriptor.version(),
                new URL[] {jarPath.toUri().toURL()},
                PluginRuntimeFactory.class.getClassLoader());

        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            HikariDataSource dataSource = buildDataSource(pluginId, schema);
            GenericWebApplicationContext ctx = null;
            try {
                onStep.accept("migrating");
                migrations.migrate(dataSource, schema, pluginId, descriptor.version(), jarPath);
                onStep.accept("starting");

                Class<?> pluginConfigClass = Class.forName(descriptor.configuration(), true, loader);

                LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
                emfBean.setDataSource(dataSource);
                emfBean.setPackagesToScan(descriptor.basePackage());
                emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                emfBean.setBeanClassLoader(loader);
                emfBean.setJpaPropertyMap(
                        Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema));
                emfBean.afterPropertiesSet();
                EntityManagerFactory emf = emfBean.getObject();

                ctx = new GenericWebApplicationContext();
                ctx.getBeanFactory().registerSingleton(PluginContextOnly.MARKER, Boolean.TRUE);
                ctx.setParent(apiContext.context());
                ctx.setClassLoader(loader);
                ctx.setServletContext(servletContext);
                ctx.getBeanFactory().registerSingleton("pluginDataSource", dataSource);
                ctx.getBeanFactory().registerSingleton("entityManagerFactory", emf);
                ctx.getBeanFactory().registerSingleton("transactionManager", new JpaTransactionManager(emf));
                // Objects bound to this plugin alone (ADR-0111), injectable like any other bean.
                for (PluginScopedBeans scoped :
                        mainContext.getBeansOfType(PluginScopedBeans.class).values()) {
                    scoped.beansFor(pluginId).forEach(ctx.getBeanFactory()::registerSingleton);
                }
                new AnnotatedBeanDefinitionReader(ctx)
                        .register(PluginInfrastructure.class, PluginMessageConverterConfig.class, pluginConfigClass);
                ctx.refresh();

                assertSecuredBeansAreProxied(ctx);

                String servletName = "plugin-" + pluginId;
                DispatcherServlet servlet = new DispatcherServlet(ctx);
                servlet.init(new PluginServletConfig(servletName, servletContext));

                PluginRuntime runtime = new PluginRuntime(
                        descriptor,
                        loader,
                        ctx,
                        servlet,
                        dataSource,
                        emf,
                        mainContext,
                        servletName,
                        jarPath,
                        sha256Hex(jarPath));
                // A bridge that throws on attach fails only this plugin's activation (PluginBridge's
                // contract) — which requires unwinding every bridge that already succeeded before it,
                // or that bridge's registration (a permission namespace, an MCP tool, a settings key)
                // would be left live for a plugin the caller was told never started.
                List<PluginBridge> attached = new ArrayList<>();
                try {
                    for (PluginBridge bridge :
                            mainContext.getBeansOfType(PluginBridge.class).values()) {
                        bridge.attach(runtime.handle());
                        attached.add(bridge);
                    }
                } catch (RuntimeException | Error attachFailure) {
                    for (int i = attached.size() - 1; i >= 0; i--) {
                        try {
                            attached.get(i).detach(runtime.handle());
                        } catch (RuntimeException detachFailure) {
                            log.warn(
                                    "Bridge {} threw unwinding plugin '{}' after a failed activation",
                                    attached.get(i),
                                    pluginId,
                                    detachFailure);
                        }
                    }
                    throw attachFailure;
                }
                return runtime;
            } catch (Exception | Error e) {
                if (ctx != null) {
                    ctx.close();
                }
                dataSource.close();
                loader.close();
                throw e;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    /** This runtime's own artifact sha256, computed once from the already-materialized jar rather
     * than re-read from {@code plugin_install} — {@link PluginRuntime#sha256()}, task 6.10. */
    private static String sha256Hex(Path jarPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(jarPath), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash plugin jar " + jarPath, e);
        }
    }

    private HikariDataSource buildDataSource(String pluginId, String schema) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dataSourceProperties.determineUrl());
        config.setUsername(dataSourceProperties.determineUsername());
        config.setPassword(dataSourceProperties.determinePassword());
        config.setMaximumPoolSize(3);
        config.setConnectionInitSql(
                "SET search_path TO " + schema + "; SET lock_timeout='10s'; SET statement_timeout='60s'");
        config.setPoolName("plugin-" + pluginId + "-pool");
        return new HikariDataSource(config);
    }

    /**
     * design.md's "after refresh assert": every bean whose class or methods carry
     * {@code @PreAuthorize}/{@code @PostAuthorize}/{@code @Transactional}/{@code @Validated} must
     * be an AOP proxy, or the annotation is silently a no-op — a plugin author's controller would
     * look secured and would not be.
     */
    private void assertSecuredBeansAreProxied(GenericWebApplicationContext ctx) {
        for (String name : ctx.getBeanDefinitionNames()) {
            Object bean = ctx.getBean(name);
            Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
            if (needsProxy(targetClass) && !org.springframework.aop.support.AopUtils.isAopProxy(bean)) {
                throw new IllegalStateException("Plugin bean '" + name + "' (" + targetClass
                        + ") declares @PreAuthorize/@PostAuthorize/@Transactional/@Validated but is not an AOP proxy.");
            }
        }
    }

    private boolean needsProxy(Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(type, PostAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(type, Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(type, Validated.class)) {
            return true;
        }
        for (var method : type.getMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(method, PostAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(method, Transactional.class)) {
                return true;
            }
        }
        return false;
    }

    /** A minimal, real {@link ServletConfig} — {@code DispatcherServlet.init} needs one, and the
     * spike's {@code MockServletConfig} is a test-only dependency this production code cannot use. */
    private record PluginServletConfig(String name, ServletContext servletContext) implements ServletConfig {
        @Override
        public String getServletName() {
            return name;
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}
