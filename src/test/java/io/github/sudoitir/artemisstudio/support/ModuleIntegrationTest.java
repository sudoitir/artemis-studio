package io.github.sudoitir.artemisstudio.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.artemisstudio.app.StudioFeatures;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeScheduler;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.test.ModuleTestExecution;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

/**
 * Base for a feature's own {@code @ApplicationModuleTest} (ADR-0069, task 5.14): the module and its
 * direct dependencies, with the kernel as shared modules, against the shared test database. A bean
 * a dependency needs from a module that is not bootstrapped is mocked in the subclass, so that
 * coupling stays visible there.
 *
 * <p>The composition root is not a module and is not scanned, so its two contributions are made
 * here. Every feature configuration is imported; each scans its package through the type exclude
 * filter that Spring Modulith narrows to the bootstrapped modules, so only those contribute beans.
 * The installed descriptors are those of the bootstrapped modules and of the modules they require,
 * so the kernel's registries validate the modules that are present.
 */
@Import({ModuleIntegrationTest.FeatureConfigurations.class, ModuleIntegrationTest.Installed.class})
public abstract class ModuleIntegrationTest {

    private static final String ROOT = "io.github.sudoitir.artemisstudio";
    private static final String FEATURES = ROOT + ".feature.";

    /** Background scraping would call mocked broker beans; no module test relies on it. */
    @MockitoBean
    ScrapeScheduler scrapeScheduler;

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    ModuleTestExecution execution;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for (String property : PostgresIntegrationTest.connectionProperties()) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        // The broker module contributes the brokers and subscriptions indicators; the jobs one is kernel.
        registry.add("management.endpoint.health.group.studio.include", () -> "jobs");
    }

    @Test
    void startsWithItsModuleAndDirectDependenciesOnly() {
        Set<String> bootstrapped = bootstrappedPackages(execution);
        String own = execution.getModule().getBasePackage().getName();
        List<String> featureBeanPackages = Arrays.stream(context.getBeanDefinitionNames())
                .map(context.getBeanFactory()::getSingleton)
                .filter(Objects::nonNull)
                .filter(bean -> !Mockito.mockingDetails(bean).isMock())
                .map(bean -> ClassUtils.getUserClass(bean).getPackageName())
                .filter(name -> name.startsWith(FEATURES))
                .toList();

        assertThat(featureBeanPackages).anyMatch(name -> within(name, own));
        assertThat(featureBeanPackages).allMatch(name -> bootstrapped.stream().anyMatch(base -> within(name, base)));
    }

    private static boolean within(String name, String basePackage) {
        return name.equals(basePackage) || name.startsWith(basePackage + ".");
    }

    private static Set<String> bootstrappedPackages(ModuleTestExecution execution) {
        return Stream.of(
                        Stream.of(execution.getModule()),
                        execution.getDependencies().stream(),
                        execution.getExtraIncludes().stream(),
                        execution.getModules().getSharedModules().stream())
                .flatMap(modules -> modules)
                .map(ApplicationModule::getBasePackage)
                .map(basePackage -> basePackage.getName())
                .collect(Collectors.toSet());
    }

    /** The feature configurations the composition root imports. */
    static class FeatureConfigurations implements ImportSelector {

        @Override
        public String[] selectImports(AnnotationMetadata metadata) {
            return Arrays.stream(
                            StudioFeatures.class.getAnnotation(Import.class).value())
                    .map(Class::getName)
                    .toArray(String[]::new);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Installed {

        /**
         * The descriptors of the bootstrapped modules, each declared in its module's base package, and of
         * every module they require: a required module is installed even where its beans are not needed.
         */
        @Bean
        InstalledFeatures installedFeatures(ModuleTestExecution execution) {
            Set<String> bootstrapped = bootstrappedPackages(execution);
            Map<String, FeatureDescriptor> byId = new LinkedHashMap<>();
            Deque<String> present = new ArrayDeque<>();
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT).stream()
                            .filter(type -> type.tryGetField("DESCRIPTOR").isPresent())
                            .forEach(type -> {
                                var descriptor =
                                        (FeatureDescriptor) ReflectionTestUtils.getField(type.reflect(), "DESCRIPTOR");
                                byId.put(descriptor.id(), descriptor);
                                if (bootstrapped.contains(type.getPackageName())) {
                                    present.add(descriptor.id());
                                }
                            });
            Map<String, FeatureDescriptor> installed = new LinkedHashMap<>();
            while (!present.isEmpty()) {
                FeatureDescriptor descriptor = byId.get(present.pop());
                if (installed.putIfAbsent(descriptor.id(), descriptor) == null) {
                    present.addAll(descriptor.requires());
                }
            }
            return new InstalledFeatures(List.copyOf(installed.values()));
        }
    }
}
