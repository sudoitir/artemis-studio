package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

/** Messages starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class MessagesModuleTest extends ModuleIntegrationTest {}
