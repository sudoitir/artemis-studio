package io.github.sudoitir.artemisstudio.feature.triage;

import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeProperties;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Triage starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class TriageModuleTest extends ModuleIntegrationTest {

    // Metrics and resources, direct dependencies, read samples and snapshots from the scrape module.

    @MockitoBean
    MetricSampleReaper metricSampleReaper;

    @MockitoBean
    MetricSamples metricSamples;

    @MockitoBean
    QueueSnapshots queueSnapshots;

    @MockitoBean
    ScrapeProperties scrapeProperties;

    // Events, a direct dependency, governs broker event props through the governance platform module.

    @MockitoBean
    ContentPolicy contentPolicy;
}
