package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard;
import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** SQL starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class SqlModuleTest extends ModuleIntegrationTest {

    /** The data lifecycle, a direct dependency, purges the audit trail through the audit delivery module. */
    @MockitoBean
    AuditRetentionGuard auditRetentionGuard;
}
