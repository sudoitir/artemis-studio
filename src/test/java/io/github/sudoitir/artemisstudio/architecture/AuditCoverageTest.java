package io.github.sudoitir.artemisstudio.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every operator-initiated write in a feature leaves an audit row (non-negotiable #3,
 * ADR-0071): a feature class with a public, non-read-only {@code @Transactional} method
 * must use {@link AuditService} or {@link BrokerCommands}.
 *
 * <p>The exemptions are Studio's own bookkeeping — work no operator asked for, which an
 * audit row per tick would bury. Each carries its reason, and an exemption for a class
 * that no longer writes fails the test, so the list cannot silently outlive its cause.
 */
class AuditCoverageTest {

    private static final String BASE = "io.github.sudoitir.artemisstudio.";

    private static final Set<String> AUDITORS = Set.of(AuditService.class.getName(), BrokerCommands.class.getName());

    private static final Map<String, String> SYSTEM_WRITES = Map.ofEntries(
            Map.entry("feature.alerting.AlertDispatcher", "delivers notifications Studio already queued"),
            Map.entry("feature.alerting.AlertEvaluator", "records rule state after a scrape tier"),
            Map.entry(
                    "feature.brokerconfig.BrokerConfigDriftService",
                    "records drift findings and never writes to a broker"),
            Map.entry(
                    "feature.brokerconfig.BrokerConfigRecommendationService",
                    "declares through BrokerConfigService.save, which audits"),
            Map.entry("feature.events.BrokerEventWriter", "persists broker notifications as they arrive"),
            Map.entry("feature.events.BrokerEventReaper", "trims broker events past retention"),
            Map.entry("feature.rr.RrCorrelator", "records observed request-reply flows"),
            Map.entry("feature.rr.RrDeadlineSweep", "marks flows past their deadline"),
            Map.entry("feature.rr.RrFlowReaper", "trims request-reply flows past retention"),
            Map.entry("feature.sql.CaptureLoss", "measures capture loss for the index view"),
            Map.entry("feature.identitylocal.AdminBootstrap", "creates the first administrator at startup"));

    @Test
    void everyOperatorWriteInAFeatureIsAudited() {
        List<String> unaudited = new ArrayList<>();
        Set<String> writers = new HashSet<>();
        for (JavaClass c : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE + "feature")) {
            if (c.getMethods().stream().noneMatch(AuditCoverageTest::isWrite)) {
                continue;
            }
            String name = c.getName().substring(BASE.length());
            writers.add(name);
            boolean audited = c.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> AUDITORS.contains(d.getTargetClass().getName()));
            if (!audited && !SYSTEM_WRITES.containsKey(name)) {
                unaudited.add(name);
            }
        }

        assertThat(unaudited)
                .as("write through BrokerCommands or AuditService, or add a reasoned exemption")
                .isEmpty();
        assertThat(writers)
                .as("an exemption for a class that no longer writes is stale; remove it")
                .containsAll(SYSTEM_WRITES.keySet());
    }

    private static boolean isWrite(JavaMethod m) {
        return m.getModifiers().contains(JavaModifier.PUBLIC)
                && m.isAnnotatedWith(Transactional.class)
                && !m.getAnnotationOfType(Transactional.class).readOnly();
    }
}
