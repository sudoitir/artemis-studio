package io.github.sudoitir.artemisstudio.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.app.ApiContractConfig;
import java.util.List;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails the test whose MockMvc requests broke the contract ({@link ApiContractConfig}). Auto-detected
 * (junit-platform.properties), and a no-op unless {@code -Dapi.contract=true}, when nothing records.
 */
public class ApiContractExtension implements BeforeEachCallback, AfterTestExecutionCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApiContractConfig.VIOLATIONS.get().clear();
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        List<String> violations = List.copyOf(ApiContractConfig.VIOLATIONS.get());
        ApiContractConfig.VIOLATIONS.get().clear();
        assertThat(violations).as("responses that differ from web/openapi.json").isEmpty();
    }
}
