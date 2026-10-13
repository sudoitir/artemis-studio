package io.github.sudoitir.artemisstudio.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Keys are time-ordered UUIDv7 (ADR-0192): an entity id is {@code @UuidGenerator(style = VERSION_7)} and code that
 * needs an id first calls {@code Ids.v7()}. A random v4 UUID is for the few values that are not keys, and each one is
 * listed here with its reason.
 */
class KeyGenerationRulesTest {

    private static final String ROOT = "io.github.sudoitir.artemisstudio";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /** Class to why it may still call {@code UUID.randomUUID()}. */
    private static final Set<String> RANDOM_UUID_ALLOWED = Set.of(
            // bearer handles: the id is the credential, and a v7 id is partly predictable
            ROOT + ".feature.sql.web.SqlQueryTickets",
            ROOT + ".feature.diagnostics.DiagnosticsService",
            // values that are not keys
            ROOT + ".kernel.core.RequestIds",
            ROOT + ".kernel.settings.StudioInstance",
            ROOT + ".kernel.replica.ReplicaRegistry",
            ROOT + ".feature.identitylocal.LocalIdentity",
            ROOT + ".feature.identityoidc.OidcAuthenticationSuccessHandler",
            ROOT + ".feature.alerting.NotificationChannelService",
            ROOT + ".platform.clusters.TopologyDiscovery");

    @Test
    void noEntityIdUsesTheRandomUuidStrategy() {
        classes()
                .should(new ArchCondition<com.tngtech.archunit.core.domain.JavaClass>("not use GenerationType.UUID") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass item, ConditionEvents events) {
                        item.getFields().stream()
                                .filter(f -> f.isAnnotatedWith(GeneratedValue.class))
                                .filter(f -> f.getAnnotationOfType(GeneratedValue.class.getName())
                                        .get("strategy")
                                        .map(v -> v instanceof JavaEnumConstant e
                                                && GenerationType.UUID.name().equals(e.name()))
                                        .orElse(false))
                                .forEach(f -> events.add(SimpleConditionEvent.violated(
                                        f,
                                        f.getFullName() + " uses GenerationType.UUID; use @UuidGenerator VERSION_7")));
                    }
                })
                .check(CLASSES);
    }

    @Test
    void randomUuidsAreOnlyCalledWhereAKeyIsNotMade() {
        noClasses()
                .that(new DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("are not allow-listed") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass c) {
                        return !RANDOM_UUID_ALLOWED.contains(c.getName());
                    }
                })
                .should()
                .callMethod(UUID.class, "randomUUID")
                .because("keys are UUIDv7 from Ids.v7() or the database default; see KeyGenerationRulesTest")
                .check(CLASSES);
    }
}
