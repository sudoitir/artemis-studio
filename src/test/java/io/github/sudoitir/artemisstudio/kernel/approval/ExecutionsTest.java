package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.gate.AuthKind;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionsTest {

    @Test
    void aRequestMadeWithATokenIsRefusedWhileApiTokensAreSwitchedOff() {
        Executions executions =
                new Executions(null, null, null, null, null, null, null, Optional.empty(), null, null, null, null);
        HeldRow row = new HeldRow(
                UUID.randomUUID(),
                "queue.purge",
                1,
                HeldState.APPROVED,
                null,
                AuthKind.TOKEN,
                null,
                UUID.randomUUID(),
                "alice",
                UUID.randomUUID(),
                null,
                null,
                "Purge",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0);

        assertThatThrownBy(() -> executions.requesterNow(row))
                .isInstanceOf(Executions.Refusal.class)
                .hasMessageContaining("API tokens are switched off");
    }
}
