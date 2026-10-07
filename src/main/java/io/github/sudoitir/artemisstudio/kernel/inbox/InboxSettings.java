package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** How long a read notice is kept. The retention of all notices is the Data page's (ADR-0134). */
@Component
public class InboxSettings implements SettingsContribution {

    public static final String READ_RETENTION = "inbox.read-retention";

    static final Duration DEFAULT_READ_RETENTION = Duration.ofDays(30);

    @Override
    public String featureId() {
        return InboxModule.ID;
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(new SettingDef(
                READ_RETENTION,
                "Inbox",
                "Read notice retention",
                "How long a notice is kept after its recipient read it. Unread notices stay until the inbox"
                        + " store's own retention on the Data page.",
                Kind.DURATION,
                DEFAULT_READ_RETENTION::toString,
                null,
                "1h",
                "3650d"));
    }
}
