package io.github.sudoitir.artemisstudio.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The boundaries Modulith does not see (design D6, ADR-0069). Access between modules and cycles
 * are {@link ModularityTest}'s job; these rules say which framework and client types a module may
 * touch at all.
 */
class BoundaryRulesTest {

    private static final String ROOT = "io.github.sudoitir.artemisstudio";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * The SQL tail is a per-request stream: it holds its own emitter and hands the caller's security
     * context to it. The pattern covers its nested classes.
     */
    private static final String SQL_TAIL = Pattern.quote(ROOT + ".feature.sql.web.SqlStreamController") + "(\\$.*)?";

    @Test
    void featuresUseOnlyMethodSecurityFromSpringSecurity() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .and()
                .resideOutsideOfPackages(
                        ROOT + ".feature.identitylocal..",
                        ROOT + ".feature.identityoidc..",
                        ROOT + ".feature.apitokens..")
                .and()
                .haveNameNotMatching(SQL_TAIL)
                .should()
                .dependOnClassesThat(resideInAPackage("org.springframework.security..")
                        .and(not(resideInAPackage("org.springframework.security.access.prepost.."))))
                .check(CLASSES);
    }

    @Test
    void onlyTheSecurityKernelAndRedirectSignInSeeHttpSecurity() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".kernel.security..", ROOT + ".feature.identityoidc..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.security.config.annotation.web.builders.HttpSecurity")
                .check(CLASSES);
    }

    @Test
    void entitiesAndRepositoriesLiveInAModulesPersistencePackage() {
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .or()
                .areAssignableTo(Repository.class)
                .should()
                .resideInAPackage("..internal.persistence")
                .check(CLASSES);
    }

    @Test
    void brokerClientTypesStayInsideThePlatformBroker() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".platform.broker..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.jolokia..", "org.apache.activemq.artemis.api.core.client..")
                .check(CLASSES);
    }

    @Test
    void onlyTheStreamKernelAndTheSqlTailHoldAnEmitter() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".kernel.stream..")
                .and()
                .haveNameNotMatching(SQL_TAIL)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(SseEmitter.class)
                .check(CLASSES);
    }

    @Test
    void schedulingStaysInTheJobsKernelAndTheScrapeTiers() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".kernel.jobs..", ROOT + ".platform.scrape..")
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(Scheduled.class, TaskScheduler.class, SchedulingConfigurer.class)
                .check(CLASSES);
    }

    @Test
    void featuresNeitherReachIntoTheContainerNorEnableFrameworkFeatures() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(ApplicationContext.class, BeanFactory.class)
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .should()
                .beAnnotatedWith(new DescribedPredicate<JavaAnnotation<?>>("an @Enable annotation") {
                    @Override
                    public boolean test(JavaAnnotation<?> annotation) {
                        return annotation.getRawType().getSimpleName().startsWith("Enable");
                    }
                })
                .check(CLASSES);
    }
}
