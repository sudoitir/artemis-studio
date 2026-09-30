package io.github.sudoitir.artemisstudio.kernel.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed plugin secrets, for key rotation (ADR-0132). */
@Component
class PluginSecretSealedStore extends TableSealedStore {

    PluginSecretSealedStore(JdbcTemplate jdbc) {
        super(jdbc, "plugin_secret", "id");
    }
}
