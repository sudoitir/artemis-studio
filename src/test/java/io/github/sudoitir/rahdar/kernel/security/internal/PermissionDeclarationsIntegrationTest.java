package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every permission Rahdar's own guards name is in the catalogue and described (operational-health spec). */
class PermissionDeclarationsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    PermissionDeclarations declarations;

    @Test
    void rahdarItselfHasNoPermissionMismatches() {
        assertThat(declarations.rahdarReferences()).isGreaterThan(20);
        assertThat(declarations.mismatches()).isEmpty();
    }
}
