package io.github.sudoitir.rahdar.feature.apitokens;

import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** API tokens starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class ApiTokensModuleTest extends ModuleIntegrationTest {

    /** The kernel's permission checks walk the scope hierarchy the clusters module implements. */
    @MockitoBean
    ScopeHierarchy scopeHierarchy;
}
