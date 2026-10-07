package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.security.TableSealedStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The two seals of a held operation, for key rotation (ADR-0132). The table's trigger lets a rotation re-wrap them. */
final class HeldSealedStores {

    private HeldSealedStores() {}

    @Component
    static class Payload extends TableSealedStore {
        Payload(JdbcTemplate jdbc) {
            super(jdbc, "held_operation", "sealed_payload", List.of("id"));
        }
    }

    @Component
    static class Decision extends TableSealedStore {
        Decision(JdbcTemplate jdbc) {
            super(jdbc, "held_operation", "sealed_decision", List.of("id"));
        }
    }
}
