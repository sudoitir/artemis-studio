package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.security.TableSealedStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed notification channel secrets, for key rotation (ADR-0132). */
@Component
class NotificationChannelSealedStore extends TableSealedStore {

    NotificationChannelSealedStore(JdbcTemplate jdbc) {
        super(jdbc, "notification_channel", "id");
    }
}
