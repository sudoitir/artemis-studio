package io.github.sudoitir.artemisstudio.feature.routing;

import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Routing starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class RoutingModuleTest extends ModuleIntegrationTest {

    /** Queue lifecycle, a direct dependency, locates queues through the scrape module. */
    @MockitoBean
    QueueLocator queueLocator;
}
