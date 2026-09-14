package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.feature.queues.DivertOperations;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Request-reply starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class RrModuleTest extends ModuleIntegrationTest {

    // The SQL module's capture tap, a direct dependency, creates its queues and diverts through the
    // queues module.

    @MockitoBean
    DivertOperations divertOperations;

    @MockitoBean
    QueueLifecycleOperations queueLifecycleOperations;
}
