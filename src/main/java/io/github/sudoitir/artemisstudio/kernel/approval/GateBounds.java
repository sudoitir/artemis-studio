package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** The current values of {@link ApprovalSettings}, read on every use so a change applies at once. */
@Component
class GateBounds {

    private final SettingsService settings;

    GateBounds(SettingsService settings) {
        this.settings = settings;
    }

    Duration decideTimeout() {
        return settings.duration(ApprovalSettings.DECIDE_TIMEOUT);
    }

    Duration maxHold() {
        return settings.duration(ApprovalSettings.MAX_HOLD);
    }

    Duration runWindow() {
        return settings.duration(ApprovalSettings.RUN_WINDOW);
    }

    int maxOpenPerRequester() {
        return settings.intValue(ApprovalSettings.MAX_OPEN_PER_REQUESTER);
    }

    Duration runLease() {
        return settings.duration(ApprovalSettings.RUN_LEASE);
    }
}
