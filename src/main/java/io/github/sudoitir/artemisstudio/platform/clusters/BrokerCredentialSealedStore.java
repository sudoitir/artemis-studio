package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.security.TableSealedStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed cluster and bridge credentials, for key rotation (ADR-0132). */
@Component
class BrokerCredentialSealedStore extends TableSealedStore {

    BrokerCredentialSealedStore(JdbcTemplate jdbc) {
        super(jdbc, "broker_credential", "id");
    }
}
