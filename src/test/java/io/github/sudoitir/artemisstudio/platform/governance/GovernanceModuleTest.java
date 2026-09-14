package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Data governance starts with its direct dependencies only, and its schema validates. */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class GovernanceModuleTest extends ModuleIntegrationTest {

    /** The kernel's permission checks walk the scope hierarchy the clusters module implements. */
    @MockitoBean
    ScopeHierarchy scopeHierarchy;
}
