package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every permission Studio's own guards name is in the catalogue and described (operational-health spec). */
class PermissionDeclarationsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    PermissionDeclarations declarations;

    @Test
    void studioItselfHasNoPermissionMismatches() {
        assertThat(declarations.studioReferences()).isGreaterThan(20);
        assertThat(declarations.mismatches()).isEmpty();
    }
}
