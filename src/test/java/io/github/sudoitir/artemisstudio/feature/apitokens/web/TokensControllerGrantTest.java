package io.github.sudoitir.artemisstudio.feature.apitokens.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenGrantRequest;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.TokenGrant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A grant asked for when minting a key: whole-scope, or limited to the names a pattern matches. */
class TokensControllerGrantTest {

    private final UUID cluster = UUID.randomUUID();

    @Test
    void aGrantWithNoKindOrPatternCoversItsWholeScope() {
        TokenGrant grant = TokensController.toGrant(new TokenGrantRequest("queue:read", "GLOBAL", null, null, null));

        assertThat(grant.limited()).isFalse();
        assertThat(grant.scopeId()).isEqualTo(ScopeIds.GLOBAL);
    }

    @Test
    void aGrantMayBeLimitedToTheNamesAPatternMatches() {
        TokenGrant grant = TokensController.toGrant(
                new TokenGrantRequest("message:read", "CLUSTER", cluster, "queue", "orders.#"));

        assertThat(grant.scopeType()).isEqualTo(Grant.ScopeType.CLUSTER);
        assertThat(grant.kind()).isEqualTo(ResourceKind.QUEUE);
        assertThat(grant.pattern().matches("orders.in")).isTrue();
        assertThat(grant.pattern().matches("orders2.in")).isFalse();
    }

    @Test
    void aKindWithoutAPatternIsRefused() {
        assertThatThrownBy(() -> TokensController.toGrant(
                        new TokenGrantRequest("message:read", "CLUSTER", cluster, "QUEUE", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPatternWithoutAKindIsRefused() {
        assertThatThrownBy(() -> TokensController.toGrant(
                        new TokenGrantRequest("message:read", "CLUSTER", cluster, null, "orders.#")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPatternThatDoesNotParseIsRefused() {
        assertThatThrownBy(() -> TokensController.toGrant(
                        new TokenGrantRequest("message:read", "CLUSTER", cluster, "QUEUE", "orders..in")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty word");
    }
}
